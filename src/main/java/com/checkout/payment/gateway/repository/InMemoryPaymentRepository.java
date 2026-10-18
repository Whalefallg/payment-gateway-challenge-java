package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.AttemptStatus;
import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.domain.PaymentAttempt;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.domain.PaymentTransitionPolicy;
import com.checkout.payment.gateway.enums.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryPaymentRepository implements PaymentRepository {

  private final ConcurrentHashMap<UUID, PaymentIntent> payments = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<IdempotencyIdentity, UUID> idempotency = new ConcurrentHashMap<>();
  private final Object admissionLock = new Object();
  private final PaymentTransitionPolicy transitions = new PaymentTransitionPolicy();
  private final Clock clock;

  public InMemoryPaymentRepository() {
    this(Clock.systemUTC());
  }

  public InMemoryPaymentRepository(Clock clock) {
    this.clock = clock;
  }

  @Override
  public AdmissionResult accept(String merchantId, String idempotencyKey, PaymentCommand command) {
    if (idempotencyKey == null) {
      return AdmissionResult.created(create(merchantId, null, command));
    }
    IdempotencyIdentity identity = new IdempotencyIdentity(merchantId, idempotencyKey);
    synchronized (admissionLock) {
      UUID existingId = idempotency.get(identity);
      if (existingId != null) {
        PaymentIntent existing = payments.get(existingId);
        return existing.requestFingerprint().equals(command.requestFingerprint())
            ? AdmissionResult.existing(existing) : AdmissionResult.conflict(existing);
      }
      PaymentIntent created = create(merchantId, idempotencyKey, command);
      idempotency.put(identity, created.paymentId());
      return AdmissionResult.created(created);
    }
  }

  private PaymentIntent create(String merchantId, String key, PaymentCommand command) {
    UUID paymentId = UUID.randomUUID();
    Instant now = clock.instant();
    PaymentAttempt attempt = new PaymentAttempt(UUID.randomUUID(), paymentId,
        UUID.randomUUID().toString(), AttemptStatus.READY, null, null, now, null);
    PaymentIntent intent = new PaymentIntent(paymentId, merchantId, key,
        command.requestFingerprint(), command.amount(), command.currency(),
        command.cardNumberLastFour(), command.expiryMonth(), command.expiryYear(),
        PaymentStatus.READY, List.of(attempt), now, now, 0);
    payments.put(paymentId, intent);
    return intent;
  }

  @Override
  public Optional<PaymentIntent> findById(UUID paymentId) {
    return Optional.ofNullable(payments.get(paymentId));
  }

  @Override
  public Optional<PaymentIntent> findByIdempotencyKey(String merchantId, String key) {
    if (key == null) return Optional.empty();
    UUID id = idempotency.get(new IdempotencyIdentity(merchantId, key));
    return id == null ? Optional.empty() : findById(id);
  }

  @Override
  public TransitionResult claimExecution(UUID paymentId) {
    return transition(paymentId, PaymentStatus.READY, PaymentStatus.IN_FLIGHT,
        AttemptStatus.IN_FLIGHT, null, null);
  }

  @Override
  public TransitionResult markUnknown(UUID paymentId, String errorCategory) {
    return transition(paymentId, PaymentStatus.IN_FLIGHT, PaymentStatus.UNKNOWN,
        AttemptStatus.UNKNOWN, null, errorCategory);
  }

  private TransitionResult transition(UUID paymentId, PaymentStatus expected, PaymentStatus target,
      AttemptStatus attemptStatus, String bankReference, String errorCategory) {
    AtomicReference<TransitionResult> result = new AtomicReference<>();
    payments.computeIfPresent(paymentId, (id, current) -> {
      if (current.status() != expected) {
        result.set(new TransitionResult(false, current));
        return current;
      }
      transitions.requireAllowed(current.status(), target);
      PaymentAttempt existing = current.attempts().get(0);
      Instant now = clock.instant();
      Instant completed = switch (attemptStatus) {
        case AUTHORIZED, DECLINED, FAILED_NO_EFFECT -> now;
        default -> null;
      };
      PaymentAttempt updatedAttempt = existing.withOutcome(
          attemptStatus, bankReference, errorCategory, completed);
      PaymentIntent updated = current.transitionTo(target, updatedAttempt, now);
      result.set(new TransitionResult(true, updated));
      return updated;
    });
    TransitionResult value = result.get();
    if (value != null) return value;
    throw new IllegalArgumentException("Payment does not exist: " + paymentId);
  }

  @Override
  public EvidenceResult applyDefinitiveOutcome(
      UUID paymentId, DefinitiveOutcome outcome, String bankReference) {
    AtomicReference<EvidenceResult> result = new AtomicReference<>();
    payments.computeIfPresent(paymentId, (id, current) -> {
      PaymentStatus target = outcome.paymentStatus();
      if (current.status() == target) {
        result.set(new EvidenceResult(EvidenceResult.Kind.IDEMPOTENT, current));
        return current;
      }
      if (isDefinitive(current.status())) {
        result.set(new EvidenceResult(EvidenceResult.Kind.CONFLICT, current));
        return current;
      }
      if (!transitions.isAllowed(current.status(), target)) {
        result.set(new EvidenceResult(EvidenceResult.Kind.NOT_APPLICABLE, current));
        return current;
      }
      Instant now = clock.instant();
      PaymentAttempt updatedAttempt = current.attempts().get(0).withOutcome(
          outcome.attemptStatus(), bankReference, null, now);
      PaymentIntent updated = current.transitionTo(target, updatedAttempt, now);
      result.set(new EvidenceResult(EvidenceResult.Kind.APPLIED, updated));
      return updated;
    });
    EvidenceResult value = result.get();
    if (value != null) return value;
    throw new IllegalArgumentException("Payment does not exist: " + paymentId);
  }

  private static boolean isDefinitive(PaymentStatus status) {
    return status == PaymentStatus.AUTHORIZED || status == PaymentStatus.DECLINED
        || status == PaymentStatus.FAILED_NO_EFFECT;
  }

  @Override
  public List<PaymentIntent> unresolvedBefore(Instant cutoff) {
    return payments.values().stream()
        .filter(payment -> payment.status() == PaymentStatus.IN_FLIGHT
            && payment.updatedAt().isBefore(cutoff))
        .toList();
  }

  @Override public int size() { return payments.size(); }

  @Override
  public void clear() {
    synchronized (admissionLock) {
      payments.clear();
      idempotency.clear();
    }
  }

  private record IdempotencyIdentity(String merchantId, String key) {}
}
