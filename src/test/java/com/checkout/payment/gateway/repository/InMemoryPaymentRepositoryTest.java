package com.checkout.payment.gateway.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.enums.PaymentStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InMemoryPaymentRepositoryTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(100);

  @AfterEach void stopExecutor() { executor.shutdownNow(); }

  @Test
  void oneHundredConcurrentAdmissionsCreateOneIntentAndOneAttempt() throws Exception {
    InMemoryPaymentRepository repository = repository();
    PaymentCommand command = command("fingerprint", 1050, "GBP");
    CountDownLatch ready = new CountDownLatch(100);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<AdmissionResult>> tasks = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      tasks.add(() -> {
        ready.countDown();
        start.await();
        return repository.accept("merchant", "same-key", command);
      });
    }
    List<Future<AdmissionResult>> futures = tasks.stream().map(executor::submit).toList();
    ready.await();
    start.countDown();
    List<AdmissionResult> results = new ArrayList<>();
    for (Future<AdmissionResult> future : futures) results.add(future.get());

    assertEquals(1, results.stream().filter(r -> r.kind() == AdmissionResult.Kind.CREATED).count());
    assertEquals(99, results.stream().filter(r -> r.kind() == AdmissionResult.Kind.EXISTING).count());
    assertEquals(1, repository.size());
    assertEquals(1, results.get(0).payment().attempts().size());
    assertEquals(1, results.stream().map(r -> r.payment().paymentId()).distinct().count());
  }

  @Test
  void conflictingSemanticsDoNotModifyExistingPayment() {
    InMemoryPaymentRepository repository = repository();
    var first = repository.accept("merchant", "key", command("card-a", 10, "GBP"));
    assertEquals(AdmissionResult.Kind.CONFLICT,
        repository.accept("merchant", "key", command("amount-changed", 11, "GBP")).kind());
    assertEquals(AdmissionResult.Kind.CONFLICT,
        repository.accept("merchant", "key", command("currency-changed", 10, "EUR")).kind());
    assertEquals(AdmissionResult.Kind.CONFLICT,
        repository.accept("merchant", "key", command("card-b", 10, "GBP")).kind());
    assertEquals(first.payment(), repository.findById(first.payment().paymentId()).orElseThrow());
    assertEquals(1, repository.size());
  }

  @Test
  void merchantScopesAreIndependentAndOnlyOneExecutionClaimWins() {
    InMemoryPaymentRepository repository = repository();
    var one = repository.accept("merchant-one", "key", command("fp", 10, "GBP")).payment();
    var two = repository.accept("merchant-two", "key", command("fp", 10, "GBP")).payment();
    assertFalse(one.paymentId().equals(two.paymentId()));

    var won = repository.claimExecution(one.paymentId());
    var lost = repository.claimExecution(one.paymentId());
    assertEquals(true, won.applied());
    assertEquals(false, lost.applied());
    assertEquals(1, won.payment().attempts().size());
  }

  @Test
  void contradictoryTerminalEvidenceIsReportedAndCannotOverwriteOutcome() {
    InMemoryPaymentRepository repository = repository();
    var payment = repository.accept("merchant", "key", command("fp", 10, "GBP")).payment();
    assertEquals(EvidenceResult.Kind.NOT_APPLICABLE,
        repository.applyDefinitiveOutcome(payment.paymentId(),
            DefinitiveOutcome.AUTHORIZED, null).kind());
    repository.claimExecution(payment.paymentId());
    assertEquals(EvidenceResult.Kind.APPLIED,
        repository.applyDefinitiveOutcome(payment.paymentId(),
            DefinitiveOutcome.AUTHORIZED, null).kind());
    assertEquals(EvidenceResult.Kind.IDEMPOTENT,
        repository.applyDefinitiveOutcome(payment.paymentId(),
            DefinitiveOutcome.AUTHORIZED, null).kind());
    assertEquals(EvidenceResult.Kind.CONFLICT,
        repository.applyDefinitiveOutcome(payment.paymentId(),
            DefinitiveOutcome.DECLINED, null).kind());
    assertEquals(PaymentStatus.AUTHORIZED,
        repository.findById(payment.paymentId()).orElseThrow().status());
  }

  private InMemoryPaymentRepository repository() {
    return new InMemoryPaymentRepository(Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
  }

  private PaymentCommand command(String fingerprint, int amount, String currency) {
    return new PaymentCommand(fingerprint, amount, currency, "0001", 12, 2030);
  }
}
