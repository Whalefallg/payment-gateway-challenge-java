package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.PaymentRecord;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentsRepository {

  private final ConcurrentHashMap<UUID, PaymentRecord> payments = new ConcurrentHashMap<>();

  public void add(PaymentRecord payment) {
    payments.put(payment.id(), payment);
  }

  public Optional<PaymentRecord> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

  public int size() {
    return payments.size();
  }

  public void clear() {
    payments.clear();
  }
}
