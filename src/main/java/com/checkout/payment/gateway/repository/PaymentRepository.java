package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.domain.PaymentIntent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
  AdmissionResult accept(String merchantId, String idempotencyKey, PaymentCommand command);
  Optional<PaymentIntent> findById(UUID paymentId);
  Optional<PaymentIntent> findByIdempotencyKey(String merchantId, String idempotencyKey);
  TransitionResult claimExecution(UUID paymentId);
  TransitionResult markUnknown(UUID paymentId, String errorCategory);
  EvidenceResult applyDefinitiveOutcome(
      UUID paymentId, DefinitiveOutcome outcome, String bankReference);
  List<PaymentIntent> unresolvedBefore(Instant cutoff);
  int size();
  void clear();
}
