package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.model.PaymentResponse;

public record PaymentSubmission(SubmissionType type, PaymentResponse payment) {
  public enum SubmissionType { NEW, REPLAY }

  public static PaymentSubmission newPayment(PaymentResponse payment) {
    return new PaymentSubmission(SubmissionType.NEW, payment);
  }

  public static PaymentSubmission replay(PaymentResponse payment) {
    return new PaymentSubmission(SubmissionType.REPLAY, payment);
  }
}
