package com.checkout.payment.gateway.domain;

import com.checkout.payment.gateway.enums.PaymentStatus;

/** Type-safe input for the only two definitive authorization outcomes. */
public enum DefinitiveOutcome {
  AUTHORIZED(PaymentStatus.AUTHORIZED),
  DECLINED(PaymentStatus.DECLINED);

  private final PaymentStatus paymentStatus;

  DefinitiveOutcome(PaymentStatus paymentStatus) {
    this.paymentStatus = paymentStatus;
  }

  public PaymentStatus paymentStatus() {
    return paymentStatus;
  }
}
