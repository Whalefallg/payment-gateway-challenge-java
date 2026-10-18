package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.PaymentIntent;

public record EvidenceResult(Kind kind, PaymentIntent payment) {
  public enum Kind {
    APPLIED,
    IDEMPOTENT,
    CONFLICT,
    NOT_APPLICABLE
  }
}
