package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.exception.ConflictingPaymentOutcomeException;
import com.checkout.payment.gateway.repository.EvidenceResult;
import com.checkout.payment.gateway.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The single interpretation point for authoritative bank outcome evidence. */
@Service
public class DefinitiveEvidenceService {

  private final PaymentRepository repository;

  public DefinitiveEvidenceService(PaymentRepository repository) {
    this.repository = repository;
  }

  public PaymentIntent apply(UUID paymentId, DefinitiveOutcome observed, String bankReference) {
    EvidenceResult result = repository.applyDefinitiveOutcome(
        paymentId, observed, bankReference);
    return switch (result.kind()) {
      case APPLIED, IDEMPOTENT -> result.payment();
      case CONFLICT -> throw new ConflictingPaymentOutcomeException(paymentId,
          result.payment().status(), observed.paymentStatus());
      case NOT_APPLICABLE -> throw new IllegalStateException(
          "Definitive evidence cannot be applied while payment is " + result.payment().status());
    };
  }
}
