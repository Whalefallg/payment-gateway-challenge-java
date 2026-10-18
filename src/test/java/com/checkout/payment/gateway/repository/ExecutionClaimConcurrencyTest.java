package com.checkout.payment.gateway.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.AttemptStatus;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.service.PaymentExecutionService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExecutionClaimConcurrencyTest {

  @Test
  void oneOfOneHundredDirectClaimsWinsTheReadyPayment() throws Exception {
    InMemoryPaymentRepository repository = repositoryWithReadyPayment();
    PaymentIntent payment = payment(repository);
    int contenders = 100;
    ExecutorService executor = Executors.newFixedThreadPool(contenders);
    CyclicBarrier barrier = new CyclicBarrier(contenders + 1);
    try {
      List<Future<TransitionResult>> futures = new ArrayList<>();
      for (int index = 0; index < contenders; index++) {
        futures.add(executor.submit(() -> {
          barrier.await(5, TimeUnit.SECONDS);
          return repository.claimExecution(payment.paymentId());
        }));
      }
      barrier.await(5, TimeUnit.SECONDS);

      int applied = 0;
      for (Future<TransitionResult> future : futures) {
        if (future.get(5, TimeUnit.SECONDS).applied()) applied++;
      }
      assertEquals(1, applied);
      PaymentIntent stored = payment(repository);
      assertEquals(PaymentStatus.IN_FLIGHT, stored.status());
      assertEquals(AttemptStatus.IN_FLIGHT, stored.attempts().get(0).status());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void concurrentExecutionCallsInvokeBankOnceAndKeepOneAttempt() throws Exception {
    InMemoryPaymentRepository repository = repositoryWithReadyPayment();
    PaymentIntent payment = payment(repository);
    BankClient bank = mock(BankClient.class);
    CountDownLatch bankEntered = new CountDownLatch(1);
    CountDownLatch releaseBank = new CountDownLatch(1);
    when(bank.process(any())).thenAnswer(invocation -> {
      bankEntered.countDown();
      assertTrue(releaseBank.await(5, TimeUnit.SECONDS));
      return BankOutcome.AUTHORIZED;
    });
    PaymentExecutionService execution = new PaymentExecutionService(repository, bank);
    int contenders = 20;
    ExecutorService executor = Executors.newFixedThreadPool(contenders);
    CyclicBarrier barrier = new CyclicBarrier(contenders + 1);
    CountDownLatch nonBankCallersCompleted = new CountDownLatch(contenders - 1);
    try {
      List<Future<PaymentIntent>> futures = new ArrayList<>();
      for (int index = 0; index < contenders; index++) {
        futures.add(executor.submit(() -> {
          barrier.await(5, TimeUnit.SECONDS);
          PaymentIntent result = execution.execute(payment.paymentId(), request());
          nonBankCallersCompleted.countDown();
          return result;
        }));
      }
      barrier.await(5, TimeUnit.SECONDS);
      assertTrue(bankEntered.await(5, TimeUnit.SECONDS));
      assertTrue(nonBankCallersCompleted.await(5, TimeUnit.SECONDS));
      releaseBank.countDown();
      for (Future<PaymentIntent> future : futures) future.get(5, TimeUnit.SECONDS);

      verify(bank, times(1)).process(any());
      PaymentIntent stored = payment(repository);
      assertEquals(PaymentStatus.AUTHORIZED, stored.status());
      assertEquals(AttemptStatus.AUTHORIZED, stored.attempts().get(0).status());
      assertEquals(1, stored.attempts().size());
    } finally {
      releaseBank.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private InMemoryPaymentRepository repositoryWithReadyPayment() {
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
    repository.accept("merchant", "key",
        new PaymentCommand("fingerprint", 1050, "GBP", "0001", 12, 2030));
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
