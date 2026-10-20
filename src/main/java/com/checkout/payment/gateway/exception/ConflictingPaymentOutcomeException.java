package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.util.UUID;

public class ConflictingPaymentOutcomeException extends IllegalStateException {
  public ConflictingPaymentOutcomeException(UUID paymentId, PaymentStatus existing,
      PaymentStatus observed) {
    super("Conflicting definitive outcome for payment " + paymentId
        + ": existing=" + existing + ", observed=" + observed);
  }
}
