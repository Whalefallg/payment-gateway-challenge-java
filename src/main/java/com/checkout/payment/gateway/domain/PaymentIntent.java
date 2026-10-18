package com.checkout.payment.gateway.domain;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable aggregate snapshot. It deliberately contains neither PAN nor CVV. */
public record PaymentIntent(
    UUID paymentId,
    String merchantId,
    String idempotencyKey,
    String requestFingerprint,
    int amount,
    String currency,
    String cardNumberLastFour,
    int expiryMonth,
    int expiryYear,
    PaymentStatus status,
    List<PaymentAttempt> attempts,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public PaymentIntent {
    attempts = List.copyOf(attempts);
  }

  public PaymentIntent transitionTo(PaymentStatus target, PaymentAttempt attempt, Instant now) {
    return new PaymentIntent(paymentId, merchantId, idempotencyKey, requestFingerprint,
        amount, currency, cardNumberLastFour, expiryMonth, expiryYear, target,
        List.of(attempt), createdAt, now, version + 1);
  }
}
