package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.model.PaymentResponse;

public record PaymentSubmission(Kind kind, PaymentResponse payment) {
  public enum Kind { CREATED, REPLAYED }

  public static PaymentSubmission created(PaymentResponse payment) {
    return new PaymentSubmission(Kind.CREATED, payment);
  }

  public static PaymentSubmission replayed(PaymentResponse payment) {
    return new PaymentSubmission(Kind.REPLAYED, payment);
  }
}
