package com.checkout.payment.gateway.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.domain.PaymentAttempt;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.ConflictingBankReferenceException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.service.DefinitiveEvidenceService;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import com.checkout.payment.gateway.service.RecoveryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BankReferenceArbitrationTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void stopExecutor() throws Exception {
    executor.shutdownNow();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void nullToReferenceEnrichesAndPreservesAttemptCompletionIdentity() {
    InMemoryPaymentRepository repository = authorizedRepository(null);
    PaymentIntent before = payment(repository);
    PaymentAttempt beforeAttempt = before.attempts().get(0);

    EvidenceResult result = repository.applyDefinitiveOutcome(
        before.paymentId(), DefinitiveOutcome.AUTHORIZED, "reference-A");

    assertEquals(EvidenceResult.Kind.ENRICHED, result.kind());
    assertEquals("reference-A", result.payment().attempts().get(0).bankReference());
    assertEquals(before.version() + 1, result.payment().version());
    assertEquals(beforeAttempt.completedAt(), result.payment().attempts().get(0).completedAt());
    assertEquals(beforeAttempt.attemptId(), result.payment().attempts().get(0).attemptId());
    assertEquals(beforeAttempt.bankRequestKey(), result.payment().attempts().get(0).bankRequestKey());
    assertEquals(1, result.payment().attempts().size());
    assertEquals(PaymentStatus.AUTHORIZED, result.payment().status());
  }

  @Test
  void sameReferenceIsIdempotent() {
    InMemoryPaymentRepository repository = authorizedRepository("reference-A");
    PaymentIntent before = payment(repository);
    EvidenceResult result = repository.applyDefinitiveOutcome(
        before.paymentId(), DefinitiveOutcome.AUTHORIZED, "reference-A");
    assertEquals(EvidenceResult.Kind.IDEMPOTENT, result.kind());
    assertEquals(before, result.payment());
  }

  @Test
  void missingIncomingReferenceIsIdempotentAndPreservesExistingReference() {
    InMemoryPaymentRepository repository = authorizedRepository("reference-A");
    PaymentIntent before = payment(repository);
    EvidenceResult result = repository.applyDefinitiveOutcome(
        before.paymentId(), DefinitiveOutcome.AUTHORIZED, null);
    assertEquals(EvidenceResult.Kind.IDEMPOTENT, result.kind());
    assertEquals("reference-A", result.payment().attempts().get(0).bankReference());
    assertEquals(before.version(), result.payment().version());
  }

  @Test
  void contradictoryReferenceIsAnExplicitReferenceConflict() {
    InMemoryPaymentRepository repository = authorizedRepository("reference-A");
    PaymentIntent before = payment(repository);
    EvidenceResult result = repository.applyDefinitiveOutcome(
        before.paymentId(), DefinitiveOutcome.AUTHORIZED, "reference-B");
    assertEquals(EvidenceResult.Kind.CONFLICT, result.kind());
    assertEquals(before, result.payment());
    assertThrows(ConflictingBankReferenceException.class, () ->
        new DefinitiveEvidenceService(repository).apply(
            before.paymentId(), DefinitiveOutcome.AUTHORIZED, "reference-B"));
  }

  @Test
  void bothMissingReferencesAreIdempotent() {
    InMemoryPaymentRepository repository = authorizedRepository(null);
    PaymentIntent before = payment(repository);
    EvidenceResult result = repository.applyDefinitiveOutcome(
        before.paymentId(), DefinitiveOutcome.AUTHORIZED, null);
    assertEquals(EvidenceResult.Kind.IDEMPOTENT, result.kind());
    assertEquals(before, result.payment());
  }

  @Test
  void concurrentDifferentReferencesAcceptOneAndConflictTheOther() throws Exception {
    InMemoryPaymentRepository repository = authorizedRepository(null);
    PaymentIntent payment = payment(repository);
    DefinitiveEvidenceService evidence = new DefinitiveEvidenceService(repository);
    CyclicBarrier barrier = new CyclicBarrier(3);
    Future<Object> first = executor.submit(() -> applyAfterBarrier(
        barrier, evidence, payment, "reference-A"));
    Future<Object> second = executor.submit(() -> applyAfterBarrier(
        barrier, evidence, payment, "reference-B"));
    barrier.await(5, TimeUnit.SECONDS);

    List<Object> results = List.of(
        first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
    assertEquals(1, results.stream().filter(PaymentIntent.class::isInstance).count());
    assertEquals(1, results.stream()
        .filter(ConflictingBankReferenceException.class::isInstance).count());
    PaymentIntent stored = repository.findById(payment.paymentId()).orElseThrow();
    assertEquals(payment.version() + 1, stored.version());
    assertEquals(1, stored.attempts().size());
  }

  @Test
  void concurrentDuplicateReferenceIsIdempotent() throws Exception {
    InMemoryPaymentRepository repository = authorizedRepository(null);
    PaymentIntent payment = payment(repository);
    DefinitiveEvidenceService evidence = new DefinitiveEvidenceService(repository);
    CyclicBarrier barrier = new CyclicBarrier(3);
    Future<Object> first = executor.submit(() -> applyAfterBarrier(
        barrier, evidence, payment, "reference-A"));
    Future<Object> second = executor.submit(() -> applyAfterBarrier(
        barrier, evidence, payment, "reference-A"));
    barrier.await(5, TimeUnit.SECONDS);

    assertInstanceOf(PaymentIntent.class, first.get(5, TimeUnit.SECONDS));
    assertInstanceOf(PaymentIntent.class, second.get(5, TimeUnit.SECONDS));
    PaymentIntent stored = repository.findById(payment.paymentId()).orElseThrow();
    assertEquals("reference-A", stored.attempts().get(0).bankReference());
    assertEquals(payment.version() + 1, stored.version());
    assertEquals(1, stored.attempts().size());
  }

  @Test
  void lateReferenceEnrichmentDoesNotReinvokeBankOrCreateAttempt() {
    PaymentsRepository repository = new PaymentsRepository();
    BankClient bank = mock(BankClient.class);
    when(bank.process(any())).thenReturn(BankOutcome.AUTHORIZED);
    PaymentGatewayService gateway = new PaymentGatewayService(repository, bank);
    var response = gateway.processPayment(request(), "reference-key", "merchant");
    PaymentIntent before = repository.findById(response.id()).orElseThrow();

    RecoveryService recovery = new RecoveryService(repository, Clock.systemUTC(), Duration.ZERO);
    PaymentIntent enriched = recovery.applyDefinitiveEvidence(
        response.id(), PaymentStatus.AUTHORIZED, "late-reference");

    verify(bank, times(1)).process(any());
    assertEquals(before.version() + 1, enriched.version());
    assertEquals(before.attempts().get(0).completedAt(), enriched.attempts().get(0).completedAt());
    assertEquals(before.attempts().get(0).attemptId(), enriched.attempts().get(0).attemptId());
    assertEquals(1, enriched.attempts().size());
    assertEquals("late-reference", enriched.attempts().get(0).bankReference());
  }

  private Object applyAfterBarrier(CyclicBarrier barrier, DefinitiveEvidenceService evidence,
      PaymentIntent payment, String reference) throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    try {
      return evidence.apply(payment.paymentId(), DefinitiveOutcome.AUTHORIZED, reference);
    } catch (ConflictingBankReferenceException conflict) {
      return conflict;
    }
  }

  private InMemoryPaymentRepository authorizedRepository(String reference) {
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    PaymentIntent payment = repository.accept("merchant", "key",
        new PaymentCommand("fingerprint", 1050, "GBP", "0001", 12, 2030)).payment();
    repository.claimExecution(payment.paymentId());
    assertEquals(EvidenceResult.Kind.APPLIED, repository.applyDefinitiveOutcome(
        payment.paymentId(), DefinitiveOutcome.AUTHORIZED, reference).kind());
    return repository;
  }

  private PaymentIntent payment(InMemoryPaymentRepository repository) {
    return repository.findByIdempotencyKey("merchant", "key").orElseThrow();
  }

  private PostPaymentRequest request() {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber("2222405343240001");
    request.setExpiryMonth(12);
    request.setExpiryYear(2099);
    request.setCurrency("GBP");
    request.setAmount(1050);
    request.setCvv("007");
    return request;
  }
}
