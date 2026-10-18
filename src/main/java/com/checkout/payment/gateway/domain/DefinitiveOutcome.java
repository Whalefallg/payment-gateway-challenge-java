package com.checkout.payment.gateway.domain;

import com.checkout.payment.gateway.enums.PaymentStatus;

public enum DefinitiveOutcome {
  AUTHORIZED(PaymentStatus.AUTHORIZED, AttemptStatus.AUTHORIZED),
  DECLINED(PaymentStatus.DECLINED, AttemptStatus.DECLINED),
  FAILED_NO_EFFECT(PaymentStatus.FAILED_NO_EFFECT, AttemptStatus.FAILED_NO_EFFECT);

  private final PaymentStatus paymentStatus;
  private final AttemptStatus attemptStatus;

  DefinitiveOutcome(PaymentStatus paymentStatus, AttemptStatus attemptStatus) {
    this.paymentStatus = paymentStatus;
    this.attemptStatus = attemptStatus;
  }

  public PaymentStatus paymentStatus() { return paymentStatus; }
  public AttemptStatus attemptStatus() { return attemptStatus; }
}
