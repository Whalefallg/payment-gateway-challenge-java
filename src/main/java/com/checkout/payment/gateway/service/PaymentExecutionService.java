package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentRepository;
import com.checkout.payment.gateway.repository.TransitionResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PaymentExecutionService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentExecutionService.class);

  private final PaymentRepository repository;
  private final BankClient bankClient;
  private final DefinitiveEvidenceService evidenceService;

  public PaymentExecutionService(PaymentRepository repository, BankClient bankClient) {
    this(repository, bankClient, new DefinitiveEvidenceService(repository));
  }

  @Autowired
  public PaymentExecutionService(PaymentRepository repository, BankClient bankClient,
      DefinitiveEvidenceService evidenceService) {
    this.repository = repository;
    this.bankClient = bankClient;
    this.evidenceService = evidenceService;
  }

  public PaymentIntent execute(UUID paymentId, PostPaymentRequest request) {
    TransitionResult claim = repository.claimExecution(paymentId);
    if (!claim.applied()) return claim.payment();
    LOG.info("Payment execution claimed payment_id={} attempt_id={}", paymentId,
        claim.payment().attempts().get(0).attemptId());

    BankOutcome outcome;
    try {
      outcome = bankClient.process(request);
    } catch (GatewayException bankFailure) {
      PaymentIntent updated = recordUncertainOutcome(
          paymentId, bankFailure.getErrorCode(), bankFailure);
      if (isDefinitive(updated)) {
        LOG.info("Mapped bank failure arrived after definitive evidence payment_id={} status={}",
            paymentId, updated.status());
        return updated;
      }
      throw bankFailure.withPayment(paymentId, updated.status());
    } catch (RuntimeException unexpectedBankFailure) {
      // The invocation may already have crossed the network boundary. Preserve UNKNOWN when
      // possible, but rethrow the programming/runtime failure rather than disguising it.
      recordUncertainOutcome(paymentId, "BANK_INVOCATION_UNEXPECTED", unexpectedBankFailure);
      throw unexpectedBankFailure;
    }
    DefinitiveOutcome observed = outcome == BankOutcome.AUTHORIZED
        ? DefinitiveOutcome.AUTHORIZED : DefinitiveOutcome.DECLINED;
    PaymentIntent completed = evidenceService.apply(paymentId, observed, null);
    LOG.info("Definitive bank outcome applied payment_id={} status={}",
        paymentId, completed.status());
    return completed;
  }

  private PaymentIntent recordUncertainOutcome(UUID paymentId, String category,
      RuntimeException bankFailure) {
    try {
      TransitionResult result = repository.markUnknown(paymentId, category);
      PaymentIntent current = result.payment();
      if (result.applied()) {
        LOG.warn("Payment outcome uncertain payment_id={} category={}", paymentId, category);
      }
      if (current.status() == PaymentStatus.UNKNOWN
          || isDefinitive(current)) {
        return current;
      }
      throw new IllegalStateException(
          "Bank invocation failed while payment was " + current.status());
    } catch (RuntimeException localFailure) {
      if (localFailure != bankFailure) localFailure.addSuppressed(bankFailure);
      throw localFailure;
    }
  }

  private static boolean isDefinitive(PaymentIntent payment) {
    return payment.status() == PaymentStatus.AUTHORIZED
        || payment.status() == PaymentStatus.DECLINED
        || payment.status() == PaymentStatus.FAILED_NO_EFFECT;
  }
}
