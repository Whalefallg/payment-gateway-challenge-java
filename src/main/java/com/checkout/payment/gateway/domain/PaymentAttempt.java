package com.checkout.payment.gateway.domain;

import java.time.Instant;
import java.util.UUID;

public record PaymentAttempt(
    UUID attemptId,
    UUID paymentId,
    String bankRequestKey,
    AttemptStatus status,
    String bankReference,
    String errorCategory,
    Instant startedAt,
    Instant completedAt) {

  public PaymentAttempt withOutcome(AttemptStatus newStatus, String reference,
      String error, Instant completed) {
    return new PaymentAttempt(attemptId, paymentId, bankRequestKey, newStatus,
        reference, error, startedAt, completed);
  }
}
