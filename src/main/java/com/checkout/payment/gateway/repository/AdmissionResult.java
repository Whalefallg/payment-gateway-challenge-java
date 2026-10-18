package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.PaymentIntent;

public record AdmissionResult(Kind kind, PaymentIntent payment) {
  public enum Kind { CREATED, EXISTING, CONFLICT }

  public static AdmissionResult created(PaymentIntent payment) {
    return new AdmissionResult(Kind.CREATED, payment);
  }

  public static AdmissionResult existing(PaymentIntent payment) {
    return new AdmissionResult(Kind.EXISTING, payment);
  }

  public static AdmissionResult conflict(PaymentIntent payment) {
    return new AdmissionResult(Kind.CONFLICT, payment);
  }
}
