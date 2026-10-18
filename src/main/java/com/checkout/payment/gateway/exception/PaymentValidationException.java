package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse.ErrorDetail;
import java.util.List;

public class PaymentValidationException extends RuntimeException {
  private final List<ErrorDetail> details;

  public PaymentValidationException(List<ErrorDetail> details) {
    super("Payment request validation failed", null, false, false);
    this.details = List.copyOf(details);
  }

  public List<ErrorDetail> getDetails() { return details; }
}
