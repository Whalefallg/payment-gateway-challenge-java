package com.checkout.payment.gateway.repository;

import java.time.Clock;
import com.checkout.payment.gateway.model.PaymentRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class PaymentsRepository extends InMemoryPaymentRepository {
  public PaymentsRepository() {
    super();
  }

  @Autowired
  public PaymentsRepository(Clock clock) {
    super(clock);
  }

  /** V1 test compatibility view over the V2 aggregate. */
  public Optional<PaymentRecord> get(UUID id) {
    return findById(id).map(payment -> new PaymentRecord(payment.paymentId(), payment.status(),
        payment.cardNumberLastFour(), payment.expiryMonth(), payment.expiryYear(),
        payment.currency(), payment.amount()));
  }
}
