package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.repository.PaymentRepository;
import com.checkout.payment.gateway.repository.TransitionResult;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Makes only safe in-memory recovery decisions. It never submits an authorization.
 */
@Service
public class RecoveryService {

  private static final Logger LOG = LoggerFactory.getLogger(RecoveryService.class);

  private final PaymentRepository repository;
  private final Clock clock;
  private final Duration staleAfter;
  private final DefinitiveEvidenceService evidenceService;

  public RecoveryService(PaymentRepository repository, Clock clock,
      @Value("${payment.recovery.stale-after:30s}") Duration staleAfter) {
    this(repository, clock, staleAfter, new DefinitiveEvidenceService(repository));
  }

  @Autowired
  public RecoveryService(PaymentRepository repository, Clock clock,
      @Value("${payment.recovery.stale-after:30s}") Duration staleAfter,
      DefinitiveEvidenceService evidenceService) {
    this.repository = repository;
    this.clock = clock;
    this.staleAfter = staleAfter;
    this.evidenceService = evidenceService;
  }

  public List<PaymentIntent> markStaleInFlightUnknown() {
    List<PaymentIntent> changed = repository.unresolvedBefore(clock.instant().minus(staleAfter))
        .stream()
        .map(payment -> repository.markUnknown(
            payment.paymentId(), "STALE_IN_FLIGHT_OUTCOME_UNCERTAIN"))
        .filter(TransitionResult::applied)
        .map(TransitionResult::payment)
        .toList();
    changed.forEach(payment -> LOG.warn("Stale payment marked uncertain payment_id={}",
        payment.paymentId()));
    return changed;
  }

  public PaymentIntent applyDefinitiveEvidence(UUID paymentId, PaymentStatus observed,
      String bankReference) {
    if (observed != PaymentStatus.AUTHORIZED && observed != PaymentStatus.DECLINED
        && observed != PaymentStatus.FAILED_NO_EFFECT) {
      throw new IllegalArgumentException("Evidence must be a definitive outcome");
    }
    DefinitiveOutcome outcome = switch (observed) {
      case AUTHORIZED -> DefinitiveOutcome.AUTHORIZED;
      case DECLINED -> DefinitiveOutcome.DECLINED;
      case FAILED_NO_EFFECT -> DefinitiveOutcome.FAILED_NO_EFFECT;
      default -> throw new IllegalArgumentException("Evidence must be definitive");
    };
    PaymentIntent updated = evidenceService.apply(paymentId, outcome, bankReference);
    LOG.info("Definitive recovery evidence accepted payment_id={} status={}", paymentId, observed);
    return updated;
  }

  public PaymentIntent refuseUnsafeRetry(UUID paymentId) {
    PaymentIntent payment = repository.findById(paymentId)
        .orElseThrow(() -> new IllegalArgumentException("Payment does not exist: " + paymentId));
    if (payment.status() == PaymentStatus.UNKNOWN || payment.status() == PaymentStatus.IN_FLIGHT) {
      throw new IllegalStateException("Unresolved authorization must not be retried");
    }
    return payment;
  }
}
