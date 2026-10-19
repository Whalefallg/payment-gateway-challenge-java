package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.PaymentRecord;

public record AdmissionResult(Kind kind, PaymentRecord payment) {
  public enum Kind { CREATED, EXISTING, CONFLICT }

  public static AdmissionResult created(PaymentRecord payment) {
    return new AdmissionResult(Kind.CREATED, payment);
  }

  public static AdmissionResult existing(PaymentRecord payment) {
    return new AdmissionResult(Kind.EXISTING, payment);
  }

  public static AdmissionResult conflict(PaymentRecord payment) {
    return new AdmissionResult(Kind.CONFLICT, payment);
  }
}
