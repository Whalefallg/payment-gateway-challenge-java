package com.checkout.payment.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.checkout.payment.gateway.domain.AttemptStatus;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.repository.InMemoryPaymentRepository;
import com.checkout.payment.gateway.repository.PaymentCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecoveryServiceTest {

  @Test
  void staleInFlightBecomesUnknownAndAcceptsLateDefinitiveEvidence() {
    Clock createdAt = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository(createdAt);
    var payment = repository.accept("merchant", "key",
        new PaymentCommand("fp", 10, "GBP", "0001", 12, 2030)).payment();
    repository.transition(payment.paymentId(), PaymentStatus.READY, PaymentStatus.IN_FLIGHT,
        AttemptStatus.IN_FLIGHT, null, null);
    RecoveryService recovery = new RecoveryService(repository,
        Clock.fixed(Instant.parse("2026-01-01T00:01:00Z"), ZoneOffset.UTC),
        Duration.ofSeconds(30));

    assertEquals(PaymentStatus.UNKNOWN, recovery.markStaleInFlightUnknown().get(0).status());
    var authorized = recovery.applyDefinitiveEvidence(
        payment.paymentId(), PaymentStatus.AUTHORIZED, "safe-bank-reference");
    assertEquals(PaymentStatus.AUTHORIZED, authorized.status());
    assertEquals("safe-bank-reference", authorized.attempts().get(0).bankReference());
    assertEquals(1, authorized.attempts().size());
    assertThrows(IllegalStateException.class, () -> recovery.applyDefinitiveEvidence(
        payment.paymentId(), PaymentStatus.DECLINED, "conflicting-reference"));
  }

  @Test
  void unresolvedPaymentExplicitlyRefusesRetry() {
    InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
    var payment = repository.accept("merchant", "key",
        new PaymentCommand("fp", 10, "GBP", "0001", 12, 2030)).payment();
    repository.transition(payment.paymentId(), PaymentStatus.READY, PaymentStatus.IN_FLIGHT,
        AttemptStatus.IN_FLIGHT, null, null);
    repository.transition(payment.paymentId(), PaymentStatus.IN_FLIGHT, PaymentStatus.UNKNOWN,
        AttemptStatus.UNKNOWN, null, "BANK_TIMEOUT");
    RecoveryService recovery = new RecoveryService(repository, Clock.systemUTC(), Duration.ZERO);
    assertThrows(IllegalStateException.class,
        () -> recovery.refuseUnsafeRetry(payment.paymentId()));
  }
}
