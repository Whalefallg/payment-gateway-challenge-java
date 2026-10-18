package com.checkout.payment.gateway.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentAggregateInvariantTest {

  @Test
  void paymentAndAttemptStatusesMustAgree() {
    UUID paymentId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    PaymentAttempt readyAttempt = new PaymentAttempt(UUID.randomUUID(), paymentId, "bank-key",
        AttemptStatus.READY, null, null, now, null);

    assertThrows(IllegalArgumentException.class, () -> new PaymentIntent(paymentId, "merchant",
        "key", "fingerprint", 10, "GBP", "0001", 12, 2030,
        PaymentStatus.IN_FLIGHT, List.of(readyAttempt), now, now, 0));
  }

  @Test
  void terminalAttemptRequiresCompletionTimestamp() {
    assertThrows(IllegalArgumentException.class, () -> new PaymentAttempt(
        UUID.randomUUID(), UUID.randomUUID(), "bank-key", AttemptStatus.AUTHORIZED,
        null, null, Instant.parse("2026-01-01T00:00:00Z"), null));
  }
}
