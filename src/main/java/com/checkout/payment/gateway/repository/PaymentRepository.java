package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.AttemptStatus;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
  AdmissionResult accept(String merchantId, String idempotencyKey, PaymentCommand command);
  Optional<PaymentIntent> findById(UUID paymentId);
  Optional<PaymentIntent> findByIdempotencyKey(String merchantId, String idempotencyKey);
  TransitionResult transition(UUID paymentId, PaymentStatus expected, PaymentStatus target,
      AttemptStatus attemptStatus, String bankReference, String errorCategory);
  List<PaymentIntent> unresolvedBefore(Instant cutoff);
  int size();
  void clear();
}
