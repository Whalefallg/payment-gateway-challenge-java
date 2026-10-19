package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.ConflictingPaymentOutcomeException;
import com.checkout.payment.gateway.model.PaymentRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

  private final ConcurrentHashMap<UUID, PaymentRecord> payments = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<IdempotencyIdentity, UUID> idempotency = new ConcurrentHashMap<>();
  private final Object admissionLock = new Object();
  private final Clock clock;

  public InMemoryPaymentRepository(Clock clock) {
    this.clock = clock;
  }

  @Override
  public AdmissionResult admitAndClaim(
      String merchantId, String idempotencyKey, PaymentAdmissionCommand command) {
    if (idempotencyKey == null) {
      PaymentRecord created = newRecord(merchantId, null, command, PaymentStatus.IN_FLIGHT);
      payments.put(created.paymentId(), created);
      return AdmissionResult.created(created);
    }
    IdempotencyIdentity identity = new IdempotencyIdentity(merchantId, idempotencyKey);
    synchronized (admissionLock) {
      UUID existingId = idempotency.get(identity);
      if (existingId != null) {
        PaymentRecord existing = payments.get(existingId);
        return existing.requestFingerprint().equals(command.requestFingerprint())
            ? AdmissionResult.existing(existing) : AdmissionResult.conflict(existing);
      }
      PaymentRecord created = newRecord(
          merchantId, idempotencyKey, command, PaymentStatus.IN_FLIGHT);
      payments.put(created.paymentId(), created);
      idempotency.put(identity, created.paymentId());
      return AdmissionResult.created(created);
    }
  }

  private PaymentRecord newRecord(
      String merchantId, String key, PaymentAdmissionCommand command, PaymentStatus status) {
    Instant now = clock.instant();
    Instant completedAt = isDefinitive(status) ? now : null;
    return new PaymentRecord(UUID.randomUUID(), merchantId, key, command.requestFingerprint(),
        command.amount(), command.currency(), command.cardNumberLastFour(), command.expiryMonth(),
        command.expiryYear(), status, now, now, completedAt);
  }

  @Override
  public Optional<PaymentRecord> findById(UUID paymentId) {
    return Optional.ofNullable(payments.get(paymentId));
  }

  @Override
  public Optional<PaymentRecord> findByIdempotencyKey(String merchantId, String key) {
    if (key == null) return Optional.empty();
    UUID id = idempotency.get(new IdempotencyIdentity(merchantId, key));
    return id == null ? Optional.empty() : findById(id);
  }

  @Override
  public PaymentRecord markUnknown(UUID paymentId) {
    AtomicReference<PaymentRecord> result = new AtomicReference<>();
    payments.computeIfPresent(paymentId, (id, current) -> {
      if (current.status() != PaymentStatus.IN_FLIGHT) {
        result.set(current);
        return current;
      }
      PaymentRecord updated = withStatus(current, PaymentStatus.UNKNOWN, clock.instant());
      result.set(updated);
      return updated;
    });
    if (result.get() != null) return result.get();
    throw new IllegalArgumentException("Payment does not exist: " + paymentId);
  }

  @Override
  public PaymentRecord complete(UUID paymentId, DefinitiveOutcome outcome) {
    AtomicReference<PaymentRecord> result = new AtomicReference<>();
    payments.computeIfPresent(paymentId, (id, current) -> {
      PaymentStatus target = outcome.paymentStatus();
      if (current.status() == target) {
        result.set(current);
        return current;
      }
      if (isDefinitive(current.status())) {
        throw new ConflictingPaymentOutcomeException(paymentId, current.status(), target);
      }
      if (current.status() != PaymentStatus.IN_FLIGHT) {
        throw new IllegalStateException(
            "Definitive outcome cannot be applied while payment is " + current.status());
      }
      PaymentRecord updated = withStatus(current, target, clock.instant());
      result.set(updated);
      return updated;
    });
    if (result.get() != null) return result.get();
    throw new IllegalArgumentException("Payment does not exist: " + paymentId);
  }

  private static PaymentRecord withStatus(
      PaymentRecord current, PaymentStatus status, Instant now) {
    return new PaymentRecord(current.paymentId(), current.merchantId(), current.idempotencyKey(),
        current.requestFingerprint(), current.amount(), current.currency(),
        current.cardNumberLastFour(), current.expiryMonth(), current.expiryYear(), status,
        current.createdAt(), now, isDefinitive(status) ? now : null);
  }

  private static boolean isDefinitive(PaymentStatus status) {
    return status == PaymentStatus.AUTHORIZED || status == PaymentStatus.DECLINED;
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
