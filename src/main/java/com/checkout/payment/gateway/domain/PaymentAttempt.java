package com.checkout.payment.gateway.domain;

import java.time.Instant;
import java.util.Objects;
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

  public PaymentAttempt {
    Objects.requireNonNull(attemptId, "attemptId");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(bankRequestKey, "bankRequestKey");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(startedAt, "startedAt");
    boolean terminal = status == AttemptStatus.AUTHORIZED || status == AttemptStatus.DECLINED
        || status == AttemptStatus.FAILED_NO_EFFECT;
    if (terminal != (completedAt != null)) {
      throw new IllegalArgumentException(
          "Attempt completion timestamp must match terminal status");
    }
  }

  public PaymentAttempt withOutcome(AttemptStatus newStatus, String reference,
      String error, Instant completed) {
    return new PaymentAttempt(attemptId, paymentId, bankRequestKey, newStatus,
        reference, error, startedAt, completed);
  }

  public PaymentAttempt withBankReference(String reference) {
    return new PaymentAttempt(attemptId, paymentId, bankRequestKey, status,
        reference, errorCategory, startedAt, completedAt);
  }
}
