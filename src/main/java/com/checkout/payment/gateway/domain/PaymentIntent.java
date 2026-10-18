package com.checkout.payment.gateway.domain;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(merchantId, "merchantId");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(cardNumberLastFour, "cardNumberLastFour");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (attempts.size() != 1) {
      throw new IllegalArgumentException("V2-Core requires exactly one authorization attempt");
    }
    PaymentAttempt attempt = attempts.get(0);
    if (!attempt.paymentId().equals(paymentId)) {
      throw new IllegalArgumentException("Attempt belongs to a different payment");
    }
    AttemptStatus requiredAttemptStatus;
    try {
      requiredAttemptStatus = AttemptStatus.valueOf(status.name());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Payment status cannot be represented by an attempt", ex);
    }
    if (attempt.status() != requiredAttemptStatus) {
      throw new IllegalArgumentException("Payment and attempt statuses must agree");
    }
    if (version < 0 || updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("Payment aggregate version/timestamps are invalid");
    }
  }

  public PaymentIntent transitionTo(PaymentStatus target, PaymentAttempt attempt, Instant now) {
    return new PaymentIntent(paymentId, merchantId, idempotencyKey, requestFingerprint,
        amount, currency, cardNumberLastFour, expiryMonth, expiryYear, target,
        List.of(attempt), createdAt, now, version + 1);
  }
}
