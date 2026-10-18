package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.domain.PaymentIntent;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.exception.PaymentValidationException;
import com.checkout.payment.gateway.idempotency.RequestFingerprint;
import com.checkout.payment.gateway.model.ErrorResponse.ErrorDetail;
import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.AdmissionResult;
import com.checkout.payment.gateway.repository.PaymentCommand;
import com.checkout.payment.gateway.repository.PaymentRepository;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.UUID;
import java.util.Comparator;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  public static final String DEFAULT_MERCHANT = "test-merchant";

  private final PaymentRepository paymentsRepository;
  private final BankClient bankClient;
  private final PaymentExecutionService executionService;
  private final RequestFingerprint fingerprint;
  private final Validator validator;
  private final DefinitiveEvidenceService evidenceService;

  @Autowired
  public PaymentGatewayService(PaymentRepository paymentsRepository, BankClient bankClient,
      PaymentExecutionService executionService, RequestFingerprint fingerprint,
      Validator validator, DefinitiveEvidenceService evidenceService) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
    this.executionService = executionService;
    this.fingerprint = fingerprint;
    this.validator = validator;
    this.evidenceService = evidenceService;
  }

  /** Kept for the V1 unit-level API; production wiring uses the injected constructor. */
  public PaymentGatewayService(PaymentsRepository paymentsRepository, BankClient bankClient) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
    this.executionService = new PaymentExecutionService(paymentsRepository, bankClient);
    this.fingerprint = new RequestFingerprint("unit-test-fingerprint-secret-32-bytes");
    this.validator = null;
    this.evidenceService = new DefinitiveEvidenceService(paymentsRepository);
  }

  public PaymentResponse getPaymentById(UUID id) {
    return paymentsRepository.findById(id)
        .map(PaymentGatewayService::toResponse)
        .orElseThrow(() -> new GatewayException(
            HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment was not found"));
  }

  public PaymentResponse processPayment(PostPaymentRequest request) {
    return processPayment(request, null, DEFAULT_MERCHANT);
  }

  public PaymentResponse processPayment(PostPaymentRequest request, String idempotencyKey,
      String merchantId) {
    return submitPayment(request, idempotencyKey, merchantId).payment();
  }

  public PaymentSubmission submitPayment(PostPaymentRequest request, String idempotencyKey,
      String merchantId) {
    if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 255)) {
      throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must contain between 1 and 255 characters");
    }
    Set<ConstraintViolation<PostPaymentRequest>> violations = validator == null
        ? Set.of() : validator.validate(request);
    PaymentIntent replay = idempotencyKey == null ? null
        : paymentsRepository.findByIdempotencyKey(merchantId, idempotencyKey).orElse(null);
    if (replay != null) {
      // A historical replay may use a card that has since expired. All structural constraints
      // still apply, and the fingerprint must still match the admitted payment.
      Set<ConstraintViolation<PostPaymentRequest>> structural = violations.stream()
          .filter(violation -> !violation.getConstraintDescriptor().getAnnotation()
              .annotationType().getSimpleName().equals("ValidExpiry"))
          .collect(java.util.stream.Collectors.toSet());
      rejectIfInvalid(structural);
      if (!replay.requestFingerprint().equals(fingerprint.create(request))) {
        LOG.warn("Idempotency conflict payment_id={}", replay.paymentId());
        throw new GatewayException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
            "Idempotency-Key was already used for a different payment");
      }
      LOG.info("Idempotent replay payment_id={} status={}", replay.paymentId(), replay.status());
      return PaymentSubmission.replayed(toResponse(replay));
    }
    rejectIfInvalid(violations);
    PaymentCommand command = command(request);

    // Without a key, retain the V1 failure contract: call the bank first and only store a
    // definitive result. Keyed requests use the full admitted lifecycle and UNKNOWN state.
    if (idempotencyKey == null) {
      BankOutcome outcome = bankClient.process(request);
      AdmissionResult admitted = paymentsRepository.accept(merchantId, null, command);
      paymentsRepository.claimExecution(admitted.payment().paymentId());
      DefinitiveOutcome definitive = outcome == BankOutcome.AUTHORIZED
          ? DefinitiveOutcome.AUTHORIZED : DefinitiveOutcome.DECLINED;
      return PaymentSubmission.created(toResponse(evidenceService.apply(
          admitted.payment().paymentId(), definitive, null)));
    }

    AdmissionResult admission = paymentsRepository.accept(merchantId, idempotencyKey, command);
    if (admission.kind() == AdmissionResult.Kind.CONFLICT) {
      LOG.warn("Idempotency conflict payment_id={}", admission.payment().paymentId());
      throw new GatewayException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
          "Idempotency-Key was already used for a different payment");
    }
    if (admission.kind() == AdmissionResult.Kind.EXISTING) {
      LOG.info("Concurrent idempotent replay payment_id={} status={}",
          admission.payment().paymentId(), admission.payment().status());
      return PaymentSubmission.replayed(toResponse(admission.payment()));
    }
    LOG.info("Payment admitted payment_id={} attempt_id={}", admission.payment().paymentId(),
        admission.payment().attempts().get(0).attemptId());
    return PaymentSubmission.created(
        toResponse(executionService.execute(admission.payment().paymentId(), request)));
  }

  private static void rejectIfInvalid(Set<ConstraintViolation<PostPaymentRequest>> violations) {
    if (violations.isEmpty()) return;
    var details = violations.stream()
        .map(violation -> new ErrorDetail(toSnakeCase(violation.getPropertyPath().toString()),
            violation.getMessage()))
        .sorted(Comparator.comparing(ErrorDetail::field))
        .toList();
    throw new PaymentValidationException(details);
  }

  private static String toSnakeCase(String value) {
    return value.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
  }

  private PaymentCommand command(PostPaymentRequest request) {
    String pan = request.getCardNumber();
    return new PaymentCommand(fingerprint.create(request), request.getAmount(), request.getCurrency(),
        pan.substring(pan.length() - 4), request.getExpiryMonth(), request.getExpiryYear());
  }

  private static PaymentResponse toResponse(PaymentIntent payment) {
    return new PaymentResponse(payment.paymentId(), payment.status(), payment.cardNumberLastFour(),
        payment.expiryMonth(), payment.expiryYear(), payment.currency(), payment.amount());
  }
}
