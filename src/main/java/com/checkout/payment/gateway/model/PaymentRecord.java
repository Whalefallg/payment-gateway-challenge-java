package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable persisted payment snapshot. It deliberately contains neither PAN nor CVV. */
public record PaymentRecord(
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
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public PaymentRecord {
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(merchantId, "merchantId");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(cardNumberLastFour, "cardNumberLastFour");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (status == PaymentStatus.REJECTED) {
      throw new IllegalArgumentException("Rejected is an API result, not a persisted state");
    }
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
    boolean definitive = status == PaymentStatus.AUTHORIZED || status == PaymentStatus.DECLINED;
    if (definitive != (completedAt != null)) {
      throw new IllegalArgumentException(
          "completedAt must be present only for a definitive payment outcome");
    }
    if (completedAt != null && completedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("completedAt must not be before createdAt");
    }
  }
}

