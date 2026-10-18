package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.AttemptStatus;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentRepository;
import com.checkout.payment.gateway.repository.TransitionResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentExecutionService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentExecutionService.class);

  private final PaymentRepository repository;
  private final BankClient bankClient;

  public PaymentExecutionService(PaymentRepository repository, BankClient bankClient) {
    this.repository = repository;
    this.bankClient = bankClient;
  }

  public PaymentIntent execute(UUID paymentId, PostPaymentRequest request) {
    TransitionResult claim = repository.transition(paymentId, PaymentStatus.READY,
        PaymentStatus.IN_FLIGHT, AttemptStatus.IN_FLIGHT, null, null);
    if (!claim.applied()) return claim.payment();
    LOG.info("Payment execution claimed payment_id={} attempt_id={}", paymentId,
        claim.payment().attempts().get(0).attemptId());

    try {
      BankOutcome outcome = bankClient.process(request);
      return switch (outcome) {
        case AUTHORIZED -> complete(paymentId, PaymentStatus.AUTHORIZED, AttemptStatus.AUTHORIZED);
        case DECLINED -> complete(paymentId, PaymentStatus.DECLINED, AttemptStatus.DECLINED);
      };
    } catch (GatewayException bankFailure) {
      // Once dispatch has begun, this simulator gives no evidence strong enough to prove
      // that authorization did not occur. Even a 503/connection error can follow receipt.
      PaymentStatus paymentStatus = PaymentStatus.UNKNOWN;
      AttemptStatus attemptStatus = AttemptStatus.UNKNOWN;
      PaymentIntent updated = repository.transition(paymentId, PaymentStatus.IN_FLIGHT,
          paymentStatus, attemptStatus, null, bankFailure.getErrorCode()).payment();
      LOG.warn("Payment outcome uncertain payment_id={} category={}", paymentId,
          bankFailure.getErrorCode());
      throw bankFailure.withPayment(updated.paymentId(), updated.status());
    }
  }

  private PaymentIntent complete(UUID paymentId, PaymentStatus paymentStatus,
      AttemptStatus attemptStatus) {
    TransitionResult direct = repository.transition(paymentId, PaymentStatus.IN_FLIGHT,
        paymentStatus, attemptStatus, null, null);
    if (direct.applied() || direct.payment().status() != PaymentStatus.UNKNOWN) {
      LOG.info("Payment reached outcome payment_id={} status={}", paymentId,
          direct.payment().status());
      return direct.payment();
    }
    // A stale sweep may have marked the request UNKNOWN while the original call was still
    // returning. That same call's explicit boolean result is authoritative late evidence.
    PaymentIntent late = repository.transition(paymentId, PaymentStatus.UNKNOWN, paymentStatus,
        attemptStatus, null, null).payment();
    LOG.info("Late bank outcome applied payment_id={} status={}", paymentId, late.status());
    return late;
  }
}
