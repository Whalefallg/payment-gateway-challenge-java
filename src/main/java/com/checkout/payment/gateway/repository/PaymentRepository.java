package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.model.PaymentRecord;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
  AdmissionResult admitAndClaim(String merchantId, String idempotencyKey, PaymentCommand command);
  Optional<PaymentRecord> findById(UUID paymentId);
  Optional<PaymentRecord> findByIdempotencyKey(String merchantId, String idempotencyKey);
  PaymentRecord markUnknown(UUID paymentId);
  PaymentRecord complete(UUID paymentId, DefinitiveOutcome outcome);
  int size();
  void clear();
}
