package com.checkout.payment.gateway.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.ConflictingPaymentOutcomeException;
import com.checkout.payment.gateway.model.PaymentRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InMemoryPaymentRepositoryTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(16);

  @AfterEach void stopExecutor() { executor.shutdownNow(); }

  @Test
  void concurrentAdmitAndClaimHasExactlyOneExecutionOwner() throws Exception {
    InMemoryPaymentRepository repository = repository();
    PaymentAdmissionCommand command = admissionCommand("fingerprint", 1050, "GBP");
    CountDownLatch ready = new CountDownLatch(16);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<PaymentAdmissionResult>> tasks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      tasks.add(() -> {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return repository.admitAndClaim("merchant", "same-key", command);
      });
    }
    List<Future<PaymentAdmissionResult>> futures =
        tasks.stream().map(executor::submit).toList();
    org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
    start.countDown();
    List<PaymentAdmissionResult> results = new ArrayList<>();
    for (Future<PaymentAdmissionResult> future : futures) {
      results.add(future.get(5, TimeUnit.SECONDS));
    }

    assertEquals(1, results.stream()
        .filter(r -> r.kind() == PaymentAdmissionResult.Kind.CREATED).count());
    assertEquals(15, results.stream()
        .filter(r -> r.kind() == PaymentAdmissionResult.Kind.EXISTING).count());
    PaymentRecord payment = results.get(0).payment();
    assertEquals(PaymentStatus.IN_FLIGHT, payment.status());
    assertEquals(1, results.stream().map(r -> r.payment().paymentId()).distinct().count());
    assertEquals(1, repository.size());
  }

  @Test
  void terminalAndUnknownTransitionsAreAtomicAndKeepTimestampInvariants() {
    InMemoryPaymentRepository repository = repository();
    PaymentRecord payment = repository.admitAndClaim(
        "merchant", "key", admissionCommand("fp", 10, "GBP")).payment();
    assertNull(payment.completedAt());

    PaymentRecord authorized = repository.complete(
        payment.paymentId(), DefinitiveOutcome.AUTHORIZED);
    PaymentRecord same = repository.complete(
        payment.paymentId(), DefinitiveOutcome.AUTHORIZED);

    assertEquals(authorized, same);
    assertEquals(PaymentStatus.AUTHORIZED, authorized.status());
    assertEquals(authorized.updatedAt(), authorized.completedAt());
    assertEquals(authorized, repository.markUnknown(payment.paymentId()));
    assertEquals(ConflictingPaymentOutcomeException.class,
        assertThrows(ConflictingPaymentOutcomeException.class,
            () -> repository.complete(payment.paymentId(), DefinitiveOutcome.DECLINED))
            .getClass());
    assertEquals(authorized, repository.findById(payment.paymentId()).orElseThrow());
  }

  @Test
  void unknownIsUncompletedAndHasNoOutgoingDefinitiveTransition() {
    InMemoryPaymentRepository repository = repository();
    PaymentRecord payment = repository.admitAndClaim(
        "merchant", "key", admissionCommand("fp", 10, "GBP")).payment();
    PaymentRecord unknown = repository.markUnknown(payment.paymentId());

    assertEquals(PaymentStatus.UNKNOWN, unknown.status());
    assertNull(unknown.completedAt());
    assertThrows(IllegalStateException.class,
        () -> repository.complete(payment.paymentId(), DefinitiveOutcome.AUTHORIZED));
  }

  @Test
  void concurrentOppositeDefinitiveOutcomesHaveOneWinnerAndOneExplicitConflict()
      throws Exception {
    InMemoryPaymentRepository repository = repository();
    PaymentRecord payment = repository.admitAndClaim(
        "merchant", "key", admissionCommand("fp", 10, "GBP")).payment();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Future<Object> authorized = executor.submit(() -> completeAfterStart(
        repository, payment, DefinitiveOutcome.AUTHORIZED, ready, start));
    Future<Object> declined = executor.submit(() -> completeAfterStart(
        repository, payment, DefinitiveOutcome.DECLINED, ready, start));
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    start.countDown();
    List<Object> results = List.of(
        authorized.get(5, TimeUnit.SECONDS), declined.get(5, TimeUnit.SECONDS));

    assertEquals(1, results.stream().filter(PaymentRecord.class::isInstance).count());
    assertEquals(1, results.stream()
        .filter(ConflictingPaymentOutcomeException.class::isInstance).count());
    PaymentStatus stored = repository.findById(payment.paymentId()).orElseThrow().status();
    assertEquals(1, results.stream().filter(PaymentRecord.class::isInstance)
        .map(PaymentRecord.class::cast).filter(result -> result.status() == stored).count());
  }

  @Test
  void nullKeyAdmissionsAreIndependentAndNeverEnterIdempotencyIndex() {
    InMemoryPaymentRepository repository = repository();
    PaymentAdmissionResult first = repository.admitAndClaim(
        "merchant", null, admissionCommand("fp", 10, "GBP"));
    PaymentAdmissionResult second = repository.admitAndClaim(
        "merchant", null, admissionCommand("fp", 10, "GBP"));

    assertEquals(PaymentAdmissionResult.Kind.CREATED, first.kind());
    assertEquals(PaymentAdmissionResult.Kind.CREATED, second.kind());
    assertTrue(!first.payment().paymentId().equals(second.payment().paymentId()));
    assertEquals(PaymentStatus.IN_FLIGHT, first.payment().status());
    assertEquals(first.payment().createdAt(), first.payment().updatedAt());
    assertNull(first.payment().completedAt());
    assertTrue(repository.findByIdempotencyKey("merchant", null).isEmpty());
    assertEquals(2, repository.size());
  }

  @Test
  void paymentRecordRejectsCompletionTimestampThatDoesNotMatchItsState() {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    Instant updated = created.plusSeconds(1);

    assertThrows(IllegalArgumentException.class, () -> new PaymentRecord(
        java.util.UUID.randomUUID(), "merchant", "key", "fingerprint", 10, "GBP", "0001",
        12, 2030, PaymentStatus.UNKNOWN, created, updated, updated));
    assertThrows(IllegalArgumentException.class, () -> new PaymentRecord(
        java.util.UUID.randomUUID(), "merchant", "key", "fingerprint", 10, "GBP", "0001",
        12, 2030, PaymentStatus.AUTHORIZED, created, updated, created));
  }

  private InMemoryPaymentRepository repository() {
    return new InMemoryPaymentRepository(Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
  }

  private static Object completeAfterStart(InMemoryPaymentRepository repository,
      PaymentRecord payment, DefinitiveOutcome outcome, CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    ready.countDown();
    assertTrue(start.await(5, TimeUnit.SECONDS));
    try {
      return repository.complete(payment.paymentId(), outcome);
    } catch (RuntimeException conflict) {
      return conflict;
    }
  }

  static PaymentAdmissionCommand admissionCommand(
      String fingerprint, int amount, String currency) {
    return new PaymentAdmissionCommand(fingerprint, amount, currency, "0001", 12, 2030);
  }
}
