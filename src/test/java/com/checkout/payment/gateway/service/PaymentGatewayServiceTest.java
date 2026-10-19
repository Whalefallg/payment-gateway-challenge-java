package com.checkout.payment.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.idempotency.RequestFingerprint;
import com.checkout.payment.gateway.model.PaymentRecord;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.AdmissionResult;
import com.checkout.payment.gateway.repository.InMemoryPaymentRepository;
import com.checkout.payment.gateway.repository.PaymentCommand;
import jakarta.validation.Validator;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentGatewayServiceTest {

  @Test
  void unkeyedRequestsAreIndependentAdmissionsBeforeBankInvocation() {
    BankClient bank = mock(BankClient.class);
    java.util.concurrent.atomic.AtomicReference<PaymentRecord> admitted =
        new java.util.concurrent.atomic.AtomicReference<>();
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.systemUTC()) {
      @Override
      public AdmissionResult admitAndClaim(
          String merchantId, String idempotencyKey, PaymentCommand command) {
        AdmissionResult result = super.admitAndClaim(merchantId, idempotencyKey, command);
        admitted.set(result.payment());
        return result;
      }
    };
    PaymentGatewayService service = service(repository, bank);
    java.util.concurrent.atomic.AtomicInteger bankCalls = new java.util.concurrent.atomic.AtomicInteger();
    when(bank.process(any())).thenAnswer(invocation -> {
      int call = bankCalls.incrementAndGet();
      assertEquals(call, repository.size(), "payment must be admitted before bank I/O");
      assertEquals(PaymentStatus.IN_FLIGHT,
          repository.findById(admitted.get().paymentId()).orElseThrow().status());
      return call == 1 ? BankOutcome.AUTHORIZED : BankOutcome.DECLINED;
    });

    var first = service.processPayment(request(1050, "GBP", "0001"));
    var second = service.processPayment(request(1050, "GBP", "0001"));

    assertNotEquals(first.id(), second.id());
    assertEquals(PaymentStatus.AUTHORIZED, first.status());
    assertEquals(PaymentStatus.DECLINED, second.status());
    assertEquals(2, repository.size());
    assertEquals("0001", repository.findById(first.id()).orElseThrow().cardNumberLastFour());
  }

  @Test
  void unkeyedBankFailurePersistsQueryableUnknownWithPaymentIdentity() {
    BankClient bank = mock(BankClient.class);
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.systemUTC());
    PaymentGatewayService service = service(repository, bank);
    when(bank.process(any())).thenThrow(new GatewayException(
        HttpStatus.SERVICE_UNAVAILABLE, "BANK_UNAVAILABLE", "Bank is unavailable"));

    GatewayException failure = assertThrows(
        GatewayException.class, () -> service.processPayment(request(1, "GBP", "0001")));

    assertEquals(1, repository.size());
    assertEquals(PaymentStatus.UNKNOWN, failure.getPaymentStatus());
    var stored = repository.findById(failure.getPaymentId()).orElseThrow();
    assertEquals(PaymentStatus.UNKNOWN, stored.status());
    assertEquals(null, stored.completedAt());
  }

  @Test
  void repeatedUnkeyedRequestAfterUnknownCreatesAnotherPaymentAndCallsBankAgain() {
    BankClient bank = mock(BankClient.class);
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.systemUTC());
    PaymentGatewayService service = service(repository, bank);
    when(bank.process(any()))
        .thenThrow(new GatewayException(
            HttpStatus.GATEWAY_TIMEOUT, "BANK_TIMEOUT", "Bank request timed out"))
        .thenReturn(BankOutcome.AUTHORIZED);

    GatewayException first = assertThrows(GatewayException.class,
        () -> service.processPayment(request(1, "GBP", "0001")));
    var second = service.processPayment(request(1, "GBP", "0001"));

    assertNotEquals(first.getPaymentId(), second.id());
    assertEquals(PaymentStatus.UNKNOWN,
        repository.findById(first.getPaymentId()).orElseThrow().status());
    assertEquals(PaymentStatus.AUTHORIZED, second.status());
    assertEquals(2, repository.size());
    verify(bank, times(2)).process(any());
  }

  @Test
  void keyedUnknownNeverReauthorizesAndCvvOnlyChangeReplays() {
    BankClient bank = mock(BankClient.class);
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.systemUTC());
    PaymentGatewayService service = service(repository, bank);
    when(bank.process(any())).thenThrow(new GatewayException(
        HttpStatus.GATEWAY_TIMEOUT, "BANK_TIMEOUT", "Bank request timed out"));
    PostPaymentRequest first = request(1, "GBP", "0001");

    GatewayException timeout = assertThrows(GatewayException.class,
        () -> service.processPayment(first, "key", "merchant"));
    PostPaymentRequest changedCvv = request(1, "GBP", "0001");
    changedCvv.setCvv("999");

    assertEquals(PaymentStatus.UNKNOWN,
        service.processPayment(changedCvv, "key", "merchant").status());
    assertEquals(PaymentStatus.UNKNOWN,
        repository.findById(timeout.getPaymentId()).orElseThrow().status());
    verify(bank, times(1)).process(any());
  }

  @Test
  void everyMappedAmbiguousBankFailurePersistsUnknownAndNeverRetries() {
    var failures = java.util.List.of(
        new GatewayException(HttpStatus.GATEWAY_TIMEOUT,
            "BANK_TIMEOUT", "Bank request timed out"),
        new GatewayException(HttpStatus.SERVICE_UNAVAILABLE,
            "BANK_UNAVAILABLE", "Bank is unavailable"),
        new GatewayException(HttpStatus.BAD_GATEWAY,
            "BANK_RESPONSE_INVALID", "Bank returned an invalid response"));
    for (int index = 0; index < failures.size(); index++) {
      BankClient bank = mock(BankClient.class);
      InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.systemUTC());
      PaymentGatewayService service = service(repository, bank);
      when(bank.process(any())).thenThrow(failures.get(index));
      String key = "ambiguous-" + index;

      GatewayException failure = assertThrows(GatewayException.class,
          () -> service.processPayment(request(1, "GBP", "0001"), key, "merchant"));

      assertEquals(PaymentStatus.UNKNOWN, failure.getPaymentStatus());
      assertEquals(PaymentStatus.UNKNOWN,
          service.processPayment(request(1, "GBP", "0001"), key, "merchant").status());
      verify(bank, times(1)).process(any());
    }
  }

  @Test
  void everyMappedUnkeyedBankFailurePersistsUnknown() {
    var failures = java.util.List.of(
        new GatewayException(HttpStatus.GATEWAY_TIMEOUT,
            "BANK_TIMEOUT", "Bank request timed out"),
        new GatewayException(HttpStatus.SERVICE_UNAVAILABLE,
            "BANK_UNAVAILABLE", "Bank is unavailable"),
        new GatewayException(HttpStatus.BAD_GATEWAY,
            "BANK_RESPONSE_INVALID", "Bank returned an invalid response"));
    for (GatewayException bankFailure : failures) {
      BankClient bank = mock(BankClient.class);
      InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.systemUTC());
      PaymentGatewayService service = service(repository, bank);
      when(bank.process(any())).thenThrow(bankFailure);

      GatewayException response = assertThrows(GatewayException.class,
          () -> service.processPayment(request(1, "GBP", "0001")));

      var stored = repository.findById(response.getPaymentId()).orElseThrow();
      assertEquals(PaymentStatus.UNKNOWN, response.getPaymentStatus());
      assertEquals(PaymentStatus.UNKNOWN, stored.status());
      assertEquals(null, stored.completedAt());
      assertEquals(1, repository.size());
      verify(bank, times(1)).process(any());
    }
  }

  @Test
  void sameKeyDifferentPanWithSameLastFourConflictsBeforeBank() {
    BankClient bank = mock(BankClient.class);
    when(bank.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.systemUTC());
    PaymentGatewayService service = service(repository, bank);
    service.processPayment(requestWithPan("2222405343240001"), "key", "merchant");

    GatewayException conflict = assertThrows(GatewayException.class,
        () -> service.processPayment(
            requestWithPan("1111111111110001"), "key", "merchant"));

    assertEquals("IDEMPOTENCY_CONFLICT", conflict.getErrorCode());
    verify(bank, times(1)).process(any());
  }

  private static PaymentGatewayService service(
      InMemoryPaymentRepository repository, BankClient bank) {
    Validator validator = mock(Validator.class);
    when(validator.validate(any(PostPaymentRequest.class))).thenReturn(java.util.Set.of());
    return new PaymentGatewayService(repository, bank,
        new RequestFingerprint("unit-test-fingerprint-secret-32-bytes"),
        validator);
  }

  static PostPaymentRequest request(int amount, String currency, String suffix) {
    return requestWithPan("222240534324" + suffix, amount, currency);
  }

  static PostPaymentRequest requestWithPan(String pan) {
    return requestWithPan(pan, 10, "GBP");
  }

  static PostPaymentRequest requestWithPan(String pan, int amount, String currency) {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber(pan);
    request.setExpiryMonth(12);
    request.setExpiryYear(2099);
    request.setCurrency(currency);
    request.setAmount(amount);
    request.setCvv("007");
    return request;
  }
}
