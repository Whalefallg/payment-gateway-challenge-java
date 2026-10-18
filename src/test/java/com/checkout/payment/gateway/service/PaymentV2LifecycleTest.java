package com.checkout.payment.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.AttemptStatus;
import com.checkout.payment.gateway.domain.PaymentAttempt;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.idempotency.RequestFingerprint;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.validation.ValidExpiry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.lang.annotation.Annotation;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentV2LifecycleTest {

  @Test
  void storedDomainTypesHaveNoPanOrCvvFields() {
    for (Class<?> type : List.of(PaymentIntent.class, PaymentAttempt.class)) {
      var names = java.util.Arrays.stream(type.getRecordComponents())
          .map(component -> component.getName().toLowerCase())
          .toList();
      org.junit.jupiter.api.Assertions.assertFalse(names.contains("cardnumber"));
      org.junit.jupiter.api.Assertions.assertFalse(names.contains("pan"));
      org.junit.jupiter.api.Assertions.assertFalse(names.contains("cvv"));
    }
  }

  @Test
  void keyedTimeoutBecomesUnknownAndReplayDoesNotCallBankAgain() {
    BankClient bank = mock(BankClient.class);
    PaymentsRepository repository = new PaymentsRepository();
    PaymentGatewayService service = new PaymentGatewayService(repository, bank);
    when(bank.process(any())).thenThrow(new GatewayException(
        HttpStatus.GATEWAY_TIMEOUT, "BANK_TIMEOUT", "Bank request timed out"));

    GatewayException timeout = assertThrows(GatewayException.class,
        () -> service.processPayment(request(1050, "GBP", "0001"), "key", "merchant"));
    assertEquals(PaymentStatus.UNKNOWN, timeout.getPaymentStatus());
    assertEquals(PaymentStatus.UNKNOWN, repository.findById(timeout.getPaymentId()).orElseThrow().status());
    assertEquals(AttemptStatus.UNKNOWN, repository.findById(timeout.getPaymentId())
        .orElseThrow().attempts().get(0).status());

    assertEquals(PaymentStatus.UNKNOWN,
        service.processPayment(request(1050, "GBP", "0001"), "key", "merchant").status());
    verify(bank, times(1)).process(any());
    assertEquals(1, repository.size());
    assertEquals(1, repository.findById(timeout.getPaymentId()).orElseThrow().attempts().size());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void historicalReplayIgnoresOnlyANewExpiryViolation() {
    BankClient bank = mock(BankClient.class);
    when(bank.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    PaymentsRepository repository = new PaymentsRepository();
    Validator validator = mock(Validator.class);
    ConstraintViolation<PostPaymentRequest> expiryViolation = mock(ConstraintViolation.class);
    ConstraintDescriptor descriptor = mock(ConstraintDescriptor.class);
    Annotation annotation = mock(ValidExpiry.class);
    when(annotation.annotationType()).thenReturn((Class) ValidExpiry.class);
    when(descriptor.getAnnotation()).thenReturn(annotation);
    when(expiryViolation.getConstraintDescriptor()).thenReturn(descriptor);
    when(validator.validate(any(PostPaymentRequest.class))).thenReturn(Set.of(), Set.of(expiryViolation));
    PaymentGatewayService service = new PaymentGatewayService(repository, bank,
        new PaymentExecutionService(repository, bank),
        new RequestFingerprint("unit-test-fingerprint-secret"), validator);
    PostPaymentRequest sameHistoricalPayload = request(10, "GBP", "0001");

    var original = service.processPayment(sameHistoricalPayload, "historical-key", "merchant");
    var replay = service.processPayment(sameHistoricalPayload, "historical-key", "merchant");

    assertEquals(original.id(), replay.id());
    assertEquals(PaymentStatus.AUTHORIZED, replay.status());
    verify(bank, times(1)).process(any());
  }

  @Test
  void conflictsNeverInvokeBankAndDifferentMerchantsMayReuseAKey() {
    BankClient bank = mock(BankClient.class);
    when(bank.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    PaymentsRepository repository = new PaymentsRepository();
    PaymentGatewayService service = new PaymentGatewayService(repository, bank);
    service.processPayment(request(10, "GBP", "0001"), "key", "merchant-one");
    assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(GatewayException.class,
        () -> service.processPayment(request(11, "GBP", "0001"), "key", "merchant-one"))
        .getErrorCode());
    assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(GatewayException.class,
        () -> service.processPayment(request(10, "EUR", "0001"), "key", "merchant-one"))
        .getErrorCode());
    assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(GatewayException.class,
        () -> service.processPayment(request(10, "GBP", "0003"), "key", "merchant-one"))
        .getErrorCode());
    service.processPayment(request(10, "GBP", "0001"), "key", "merchant-two");
    verify(bank, times(2)).process(any());
    assertEquals(2, repository.size());
  }

  @Test
  void oneHundredConcurrentServiceCallsCauseOneBankInvocation() throws Exception {
    BankClient bank = mock(BankClient.class);
    when(bank.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    PaymentsRepository repository = new PaymentsRepository();
    PaymentGatewayService service = new PaymentGatewayService(repository, bank);
    ExecutorService pool = Executors.newFixedThreadPool(100);
    CountDownLatch ready = new CountDownLatch(100);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<PaymentStatus>> futures = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          start.await();
          return service.processPayment(request(10, "GBP", "0001"),
              "same-key", "merchant").status();
        }));
      }
      ready.await();
      start.countDown();
      for (Future<PaymentStatus> future : futures) future.get();
    } finally {
      pool.shutdownNow();
    }
    verify(bank, times(1)).process(any());
    assertEquals(1, repository.size());
    assertEquals(1, repository.findByIdempotencyKey("merchant", "same-key")
        .orElseThrow().attempts().size());
  }

  private PostPaymentRequest request(int amount, String currency, String cardSuffix) {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber("222240534324" + cardSuffix);
    request.setExpiryMonth(12);
    request.setExpiryYear(2099);
    request.setCurrency(currency);
    request.setAmount(amount);
    request.setCvv("007");
    return request;
  }
}
