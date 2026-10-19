package com.checkout.payment.gateway.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void concurrentAdmissionsHaveOneCreatorAndOneExecutionOwner() throws Exception {
    InMemoryPaymentRepository repository = repository();
    PaymentCommand command = command("fingerprint", 1050, "GBP");
    CountDownLatch ready = new CountDownLatch(16);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<AdmissionResult>> tasks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      tasks.add(() -> {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return repository.accept("merchant", "same-key", command);
      });
    }
    List<Future<AdmissionResult>> futures = tasks.stream().map(executor::submit).toList();
    org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
    start.countDown();
    List<AdmissionResult> results = new ArrayList<>();
    for (Future<AdmissionResult> future : futures) {
      results.add(future.get(5, TimeUnit.SECONDS));
    }

    assertEquals(1, results.stream().filter(r -> r.kind() == AdmissionResult.Kind.CREATED).count());
    assertEquals(15, results.stream().filter(r -> r.kind() == AdmissionResult.Kind.EXISTING).count());
    PaymentRecord payment = results.get(0).payment();
    assertEquals(true, repository.claimExecution(payment.paymentId()).applied());
    assertEquals(false, repository.claimExecution(payment.paymentId()).applied());
    assertEquals(1, repository.size());
  }

  @Test
  void terminalAndUnknownTransitionsAreAtomicAndKeepTimestampInvariants() {
    InMemoryPaymentRepository repository = repository();
    PaymentRecord payment = repository.accept(
        "merchant", "key", command("fp", 10, "GBP")).payment();
    assertNull(payment.completedAt());
    repository.claimExecution(payment.paymentId());

    PaymentRecord authorized = repository.complete(
        payment.paymentId(), DefinitiveOutcome.AUTHORIZED);
    PaymentRecord same = repository.complete(
        payment.paymentId(), DefinitiveOutcome.AUTHORIZED);

    assertEquals(authorized, same);
    assertEquals(PaymentStatus.AUTHORIZED, authorized.status());
    assertEquals(authorized.updatedAt(), authorized.completedAt());
    assertFalse(repository.markUnknown(payment.paymentId()).applied());
    assertEquals(ConflictingPaymentOutcomeException.class,
        assertThrows(ConflictingPaymentOutcomeException.class,
            () -> repository.complete(payment.paymentId(), DefinitiveOutcome.DECLINED))
            .getClass());
    assertEquals(authorized, repository.findById(payment.paymentId()).orElseThrow());
  }

  @Test
  void unknownIsUncompletedAndHasNoOutgoingDefinitiveTransition() {
    InMemoryPaymentRepository repository = repository();
    PaymentRecord payment = repository.accept(
        "merchant", "key", command("fp", 10, "GBP")).payment();
    repository.claimExecution(payment.paymentId());
    PaymentRecord unknown = repository.markUnknown(payment.paymentId()).payment();

    assertEquals(PaymentStatus.UNKNOWN, unknown.status());
    assertNull(unknown.completedAt());
    assertThrows(IllegalStateException.class,
        () -> repository.complete(payment.paymentId(), DefinitiveOutcome.AUTHORIZED));
  }

  @Test
  void unkeyedDefinitivePersistenceRejectsAdmissionAndCreatesCompletedRecord() {
    InMemoryPaymentRepository repository = repository();
    assertThrows(IllegalArgumentException.class,
        () -> repository.accept("merchant", null, command("fp", 10, "GBP")));

    PaymentRecord stored = repository.saveDefinitive(
        "merchant", command("fp", 10, "GBP"), DefinitiveOutcome.DECLINED);

    assertEquals(PaymentStatus.DECLINED, stored.status());
    assertEquals(stored.createdAt(), stored.updatedAt());
    assertEquals(stored.createdAt(), stored.completedAt());
    assertEquals(1, repository.size());
  }

  private InMemoryPaymentRepository repository() {
    return new InMemoryPaymentRepository(Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
  }

  static PaymentCommand command(String fingerprint, int amount, String currency) {
    return new PaymentCommand(fingerprint, amount, currency, "0001", 12, 2030);
  }
}

