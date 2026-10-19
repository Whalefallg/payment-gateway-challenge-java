package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.PaymentRecord;

public record PaymentAdmissionResult(Kind kind, PaymentRecord payment) {
  public enum Kind { CREATED, EXISTING, CONFLICT }

  public static PaymentAdmissionResult created(PaymentRecord payment) {
    return new PaymentAdmissionResult(Kind.CREATED, payment);
  }

  public static PaymentAdmissionResult existing(PaymentRecord payment) {
    return new PaymentAdmissionResult(Kind.EXISTING, payment);
  }

  public static PaymentAdmissionResult conflict(PaymentRecord payment) {
    return new PaymentAdmissionResult(Kind.CONFLICT, payment);
  }
}
