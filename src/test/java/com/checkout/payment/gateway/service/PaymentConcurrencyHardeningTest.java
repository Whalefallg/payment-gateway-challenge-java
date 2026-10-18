package com.checkout.payment.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.ConflictingPaymentOutcomeException;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.InMemoryPaymentRepository;
import com.checkout.payment.gateway.repository.PaymentCommand;
import com.checkout.payment.gateway.repository.PaymentRepository;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.repository.TransitionResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentConcurrencyHardeningTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(4);

  @AfterEach void stopExecutor() { executor.shutdownNow(); }

  @Test
  void concurrentConflictingRecoveryEvidenceHasOneWinnerAndOneExplicitConflict()
      throws Exception {
    InMemoryPaymentRepository repository = unknownPaymentRepository();
    PaymentIntent payment = onlyPayment(repository);
    RecoveryService recovery = recovery(repository);
    CyclicBarrier start = new CyclicBarrier(3);

    Future<Object> authorized = executor.submit(() -> applyAfterBarrier(
        start, recovery, payment, PaymentStatus.AUTHORIZED));
    Future<Object> declined = executor.submit(() -> applyAfterBarrier(
        start, recovery, payment, PaymentStatus.DECLINED));
    start.await();
    List<Object> results = List.of(authorized.get(), declined.get());

    assertEquals(1, results.stream().filter(PaymentIntent.class::isInstance).count());
    assertEquals(1, results.stream()
        .filter(ConflictingPaymentOutcomeException.class::isInstance).count());
    PaymentStatus stored = repository.findById(payment.paymentId()).orElseThrow().status();
    assertEquals(1, results.stream().filter(PaymentIntent.class::isInstance)
        .map(PaymentIntent.class::cast).filter(result -> result.status() == stored).count());
  }

  @Test
  void concurrentSameRecoveryEvidenceIsIdempotent() throws Exception {
    InMemoryPaymentRepository repository = unknownPaymentRepository();
    PaymentIntent payment = onlyPayment(repository);
    RecoveryService recovery = recovery(repository);
    CyclicBarrier start = new CyclicBarrier(3);
    Future<PaymentIntent> first = executor.submit(() -> {
      start.await();
      return recovery.applyDefinitiveEvidence(
          payment.paymentId(), PaymentStatus.AUTHORIZED, "bank-ref");
    });
    Future<PaymentIntent> second = executor.submit(() -> {
      start.await();
      return recovery.applyDefinitiveEvidence(
          payment.paymentId(), PaymentStatus.AUTHORIZED, "bank-ref");
    });
    start.await();

    assertEquals(PaymentStatus.AUTHORIZED, first.get().status());
    assertEquals(PaymentStatus.AUTHORIZED, second.get().status());
    assertEquals(PaymentStatus.AUTHORIZED,
        repository.findById(payment.paymentId()).orElseThrow().status());
  }

  @Test
  void lateAuthorizationWinsAfterStaleSweepMarkedUnknown() throws Exception {
    BankClient bank = mock(BankClient.class);
    CountDownLatch bankEntered = new CountDownLatch(1);
    CountDownLatch releaseBank = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      bankEntered.countDown();
      releaseBank.await();
      return BankOutcome.AUTHORIZED;
    });
    InMemoryPaymentRepository readyRepository = repositoryAtCreationTime();
    PaymentIntent ready = onlyPayment(readyRepository);
    PaymentExecutionService execution = new PaymentExecutionService(readyRepository, bank);
    Future<PaymentIntent> result = executor.submit(() -> execution.execute(ready.paymentId(), request()));
    bankEntered.await();

    assertEquals(1, recovery(readyRepository).markStaleInFlightUnknown().size());
    releaseBank.countDown();

    assertEquals(PaymentStatus.AUTHORIZED, result.get().status());
    assertEquals(PaymentStatus.AUTHORIZED,
        readyRepository.findById(ready.paymentId()).orElseThrow().status());
  }

  @Test
  void concurrentStaleSweepsReportExactlyOneSuccessfulTransition() throws Exception {
    InMemoryPaymentRepository repository = inFlightRepository();
    RecoveryService recovery = recovery(repository);
    CyclicBarrier start = new CyclicBarrier(3);
    Future<List<PaymentIntent>> first = executor.submit(() -> {
      start.await();
      return recovery.markStaleInFlightUnknown();
    });
    Future<List<PaymentIntent>> second = executor.submit(() -> {
      start.await();
      return recovery.markStaleInFlightUnknown();
    });
    start.await();

    assertEquals(1, first.get().size() + second.get().size());
    assertEquals(PaymentStatus.UNKNOWN, onlyPayment(repository).status());
  }

  @Test
  void slowBankDuplicateObservesInFlightAndDoesNotInvokeBankAgain() throws Exception {
    PaymentsRepository repository = new PaymentsRepository();
    BankClient bank = mock(BankClient.class);
    CountDownLatch bankEntered = new CountDownLatch(1);
    CountDownLatch releaseBank = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      bankEntered.countDown();
      releaseBank.await();
      return BankOutcome.AUTHORIZED;
    });
    PaymentGatewayService service = new PaymentGatewayService(repository, bank);
    Future<PaymentStatus> original = executor.submit(() -> service.processPayment(
        request(), "slow-key", "merchant").status());
    bankEntered.await();

    assertEquals(PaymentStatus.IN_FLIGHT,
        service.processPayment(request(), "slow-key", "merchant").status());
    releaseBank.countDown();

    assertEquals(PaymentStatus.AUTHORIZED, original.get());
    verify(bank, times(1)).process(any());
  }

  @Test
  void lateMappedBankFailureCannotOverwriteRecoveryAuthorization() throws Exception {
    InMemoryPaymentRepository repository = repositoryAtCreationTime();
    PaymentIntent payment = onlyPayment(repository);
    BankClient bank = mock(BankClient.class);
    CountDownLatch bankEntered = new CountDownLatch(1);
    CountDownLatch releaseBank = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      bankEntered.countDown();
      releaseBank.await();
      throw new GatewayException(HttpStatus.GATEWAY_TIMEOUT,
          "BANK_TIMEOUT", "Bank request timed out");
    });
    PaymentExecutionService execution = new PaymentExecutionService(repository, bank);
    Future<PaymentIntent> result = executor.submit(() -> execution.execute(payment.paymentId(), request()));
    bankEntered.await();
    recovery(repository).applyDefinitiveEvidence(
        payment.paymentId(), PaymentStatus.AUTHORIZED, "trusted-ref");
    releaseBank.countDown();

    assertEquals(PaymentStatus.AUTHORIZED, result.get().status());
    assertEquals(PaymentStatus.AUTHORIZED, onlyPayment(repository).status());
  }

  @Test
  void contradictoryLateBankAuthorizationIsExplicitAndCannotOverwriteRecoveryDecline()
      throws Exception {
    InMemoryPaymentRepository repository = repositoryAtCreationTime();
    PaymentIntent payment = onlyPayment(repository);
    BankClient bank = mock(BankClient.class);
    CountDownLatch bankEntered = new CountDownLatch(1);
    CountDownLatch releaseBank = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      bankEntered.countDown();
      releaseBank.await();
      return BankOutcome.AUTHORIZED;
    });
    Future<PaymentIntent> result = executor.submit(() ->
        new PaymentExecutionService(repository, bank).execute(payment.paymentId(), request()));
    bankEntered.await();
    recovery(repository).applyDefinitiveEvidence(
        payment.paymentId(), PaymentStatus.DECLINED, "trusted-decline");
    releaseBank.countDown();

    ExecutionException execution = assertThrows(ExecutionException.class, result::get);
    org.junit.jupiter.api.Assertions.assertInstanceOf(
        ConflictingPaymentOutcomeException.class, execution.getCause());
    assertEquals(PaymentStatus.DECLINED, onlyPayment(repository).status());
  }

  @Test
  void unexpectedBankRuntimeFailureIsRethrownAndOutcomeBecomesUnknown() {
    InMemoryPaymentRepository repository = repositoryAtCreationTime();
    PaymentIntent payment = onlyPayment(repository);
    BankClient bank = mock(BankClient.class);
    IllegalStateException programmingError = new IllegalStateException("unexpected adapter bug");
    when(bank.process(any())).thenThrow(programmingError);

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> new PaymentExecutionService(repository, bank).execute(payment.paymentId(), request()));

    assertSame(programmingError, thrown);
    assertEquals(PaymentStatus.UNKNOWN, onlyPayment(repository).status());
  }

  @Test
  void localStateFailureIsNotDisguisedAsMappedBankFailure() {
    PaymentRepository repository = mock(PaymentRepository.class);
    PaymentIntent payment = mock(PaymentIntent.class);
    com.checkout.payment.gateway.domain.PaymentAttempt attempt =
        mock(com.checkout.payment.gateway.domain.PaymentAttempt.class);
    when(attempt.attemptId()).thenReturn(java.util.UUID.randomUUID());
    when(payment.attempts()).thenReturn(List.of(attempt));
    when(repository.claimExecution(any())).thenReturn(new TransitionResult(true, payment));
    IllegalStateException localFailure = new IllegalStateException("local state update failed");
    when(repository.markUnknown(any(), any())).thenThrow(localFailure);
    BankClient bank = mock(BankClient.class);
    GatewayException bankFailure = new GatewayException(
        HttpStatus.GATEWAY_TIMEOUT, "BANK_TIMEOUT", "Bank request timed out");
    when(bank.process(any())).thenThrow(bankFailure);

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> new PaymentExecutionService(repository, bank)
            .execute(java.util.UUID.randomUUID(), request()));

    assertSame(localFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(bankFailure, thrown.getSuppressed()[0]);
  }

  private Object applyAfterBarrier(CyclicBarrier barrier, RecoveryService recovery,
      PaymentIntent payment, PaymentStatus status) throws Exception {
    barrier.await();
    try {
      return recovery.applyDefinitiveEvidence(payment.paymentId(), status, status.name());
    } catch (ConflictingPaymentOutcomeException conflict) {
      return conflict;
    }
  }

  private InMemoryPaymentRepository unknownPaymentRepository() {
    InMemoryPaymentRepository repository = repositoryAtCreationTime();
    PaymentIntent payment = onlyPayment(repository);
    repository.claimExecution(payment.paymentId());
    repository.markUnknown(payment.paymentId(), "BANK_TIMEOUT");
    return repository;
  }

  private InMemoryPaymentRepository inFlightRepository() {
    InMemoryPaymentRepository repository = repositoryAtCreationTime();
    repository.claimExecution(onlyPayment(repository).paymentId());
    return repository;
  }

  private InMemoryPaymentRepository repositoryAtCreationTime() {
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    repository.accept("merchant", "key",
        new PaymentCommand("fingerprint", 1050, "GBP", "0001", 12, 2030));
    return repository;
  }

  private RecoveryService recovery(InMemoryPaymentRepository repository) {
    return new RecoveryService(repository,
        Clock.fixed(Instant.parse("2026-01-01T00:01:00Z"), ZoneOffset.UTC),
        Duration.ofSeconds(30));
  }

  private PaymentIntent onlyPayment(InMemoryPaymentRepository repository) {
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
