package com.checkout.payment.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.checkout.payment.gateway.repository.PaymentRepository;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentV2LiteSafetyTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(4);

  @AfterEach
  void stopExecutor() {
    executor.shutdownNow();
  }

  @Test
  void blockedDuplicateObservesInFlightAndOnlyCreatedOwnerCallsBank() throws Exception {
    InMemoryPaymentRepository repository = repository();
    BankClient bank = mock(BankClient.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return BankOutcome.AUTHORIZED;
    });
    PaymentGatewayService service = service(repository, bank);
    Future<PaymentStatus> first = executor.submit(() ->
        service.processPayment(request(1, "0001"), "key", "merchant").status());
    assertTrue(entered.await(5, TimeUnit.SECONDS));

    assertEquals(PaymentStatus.IN_FLIGHT,
        service.processPayment(request(1, "0001"), "key", "merchant").status());
    release.countDown();

    assertEquals(PaymentStatus.AUTHORIZED, first.get(5, TimeUnit.SECONDS));
    verify(bank, times(1)).process(any());
  }

  @Test
  void concurrentDifferentSemanticsConflictsWithoutSecondBankCall() throws Exception {
    InMemoryPaymentRepository repository = repository();
    BankClient bank = mock(BankClient.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return BankOutcome.AUTHORIZED;
    });
    PaymentGatewayService service = service(repository, bank);
    Future<PaymentStatus> first = executor.submit(() ->
        service.processPayment(request(1, "0001"), "key", "merchant").status());
    assertTrue(entered.await(5, TimeUnit.SECONDS));

    GatewayException conflict = assertThrows(GatewayException.class,
        () -> service.processPayment(request(2, "0001"), "key", "merchant"));
    assertEquals("IDEMPOTENCY_CONFLICT", conflict.getErrorCode());
    release.countDown();

    assertEquals(PaymentStatus.AUTHORIZED, first.get(5, TimeUnit.SECONDS));
    verify(bank, times(1)).process(any());
    assertEquals(1, repository.size());
  }

  @Test
  void blockedKeyDoesNotBlockAnotherKeyAndMerchantScopesAreIndependent() throws Exception {
    InMemoryPaymentRepository repository = repository();
    BankClient bank = mock(BankClient.class);
    CountDownLatch slowEntered = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      PostPaymentRequest request = invocation.getArgument(0);
      if (request.getAmount() == 1) {
        slowEntered.countDown();
        assertTrue(releaseSlow.await(5, TimeUnit.SECONDS));
      }
      return BankOutcome.AUTHORIZED;
    });
    PaymentGatewayService service = service(repository, bank);
    Future<PaymentStatus> slow = executor.submit(() ->
        service.processPayment(request(1, "0001"), "same-key", "merchant-one").status());
    assertTrue(slowEntered.await(5, TimeUnit.SECONDS));

    Future<PaymentStatus> otherKey = executor.submit(() ->
        service.processPayment(request(2, "0001"), "other-key", "merchant-one").status());
    Future<PaymentStatus> otherMerchant = executor.submit(() ->
        service.processPayment(request(3, "0001"), "same-key", "merchant-two").status());

    assertEquals(PaymentStatus.AUTHORIZED, otherKey.get(2, TimeUnit.SECONDS));
    assertEquals(PaymentStatus.AUTHORIZED, otherMerchant.get(2, TimeUnit.SECONDS));
    releaseSlow.countDown();
    assertEquals(PaymentStatus.AUTHORIZED, slow.get(5, TimeUnit.SECONDS));
    verify(bank, times(3)).process(any());
  }

  @Test
  void localUnknownPersistenceFailureIsNotDisguisedAsBankFailure() {
    PaymentRepository repository = mock(PaymentRepository.class);
    PaymentRecord inFlight = record(PaymentStatus.IN_FLIGHT);
    when(repository.findByIdempotencyKey(any(), any())).thenReturn(java.util.Optional.empty());
    when(repository.admitAndClaim(any(), any(), any()))
        .thenReturn(AdmissionResult.created(inFlight));
    IllegalStateException localFailure = new IllegalStateException("local state update failed");
    when(repository.markUnknown(inFlight.paymentId())).thenThrow(localFailure);
    BankClient bank = mock(BankClient.class);
    GatewayException bankFailure = new GatewayException(
        HttpStatus.GATEWAY_TIMEOUT, "BANK_TIMEOUT", "Bank request timed out");
    when(bank.process(any())).thenThrow(bankFailure);

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> service(repository, bank)
            .processPayment(request(1, "0001"), "key", "merchant"));

    assertSame(localFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(bankFailure, thrown.getSuppressed()[0]);
  }

  @Test
  void unexpectedBankRuntimeIsRethrownAfterRecordingUnknown() {
    InMemoryPaymentRepository repository = repository();
    BankClient bank = mock(BankClient.class);
    IllegalStateException adapterBug = new IllegalStateException("adapter bug");
    when(bank.process(any())).thenThrow(adapterBug);
    PaymentGatewayService service = service(repository, bank);

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> service.processPayment(request(1, "0001"), "key", "merchant"));

    assertSame(adapterBug, thrown);
    assertEquals(PaymentStatus.UNKNOWN,
        repository.findByIdempotencyKey("merchant", "key").orElseThrow().status());
  }

  private static InMemoryPaymentRepository repository() {
    return new InMemoryPaymentRepository(Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
  }

  private static PaymentGatewayService service(
      PaymentRepository repository, BankClient bank) {
    Validator validator = mock(Validator.class);
    when(validator.validate(any(PostPaymentRequest.class))).thenReturn(Set.of());
    return new PaymentGatewayService(repository, bank,
        new RequestFingerprint("unit-test-fingerprint-secret-32-bytes"), validator);
  }

  private static PostPaymentRequest request(int amount, String suffix) {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber("222240534324" + suffix);
    request.setExpiryMonth(12);
    request.setExpiryYear(2099);
    request.setCurrency("GBP");
    request.setAmount(amount);
    request.setCvv("007");
    return request;
  }

  private static PaymentRecord record(PaymentStatus status) {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new PaymentRecord(UUID.randomUUID(), "merchant", "key", "fingerprint",
        1, "GBP", "0001", 12, 2099, status, now, now, null);
  }
}

