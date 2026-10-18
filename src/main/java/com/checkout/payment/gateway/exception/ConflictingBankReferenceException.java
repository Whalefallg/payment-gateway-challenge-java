package com.checkout.payment.gateway.exception;

import java.util.UUID;

public class ConflictingBankReferenceException extends IllegalStateException {
  public ConflictingBankReferenceException(UUID paymentId) {
    super("Conflicting bank reference for payment " + paymentId);
  }
}
