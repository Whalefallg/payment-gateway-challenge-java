package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.domain.DefinitiveOutcome;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.exception.PaymentValidationException;
import com.checkout.payment.gateway.idempotency.RequestFingerprint;
import com.checkout.payment.gateway.model.ErrorResponse.ErrorDetail;
import com.checkout.payment.gateway.model.PaymentRecord;
import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.AdmissionResult;
import com.checkout.payment.gateway.repository.PaymentCommand;
import com.checkout.payment.gateway.repository.PaymentRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  public static final String DEFAULT_MERCHANT = "test-merchant";

  private final PaymentRepository repository;
  private final BankClient bankClient;
  private final RequestFingerprint fingerprint;
  private final Validator validator;

  public PaymentGatewayService(PaymentRepository repository, BankClient bankClient,
      RequestFingerprint fingerprint, Validator validator) {
    this.repository = repository;
    this.bankClient = bankClient;
    this.fingerprint = fingerprint;
    this.validator = validator;
  }

  public PaymentResponse getPaymentById(UUID id) {
    return repository.findById(id)
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
    validateKey(idempotencyKey);
    Set<ConstraintViolation<PostPaymentRequest>> violations = validator.validate(request);
    PaymentRecord replay = idempotencyKey == null ? null
        : repository.findByIdempotencyKey(merchantId, idempotencyKey).orElse(null);
    if (replay != null) {
      rejectIfInvalid(structuralViolations(violations));
      if (!replay.requestFingerprint().equals(fingerprint.create(request))) {
        throw conflict(replay);
      }
      LOG.info("Idempotent replay payment_id={} status={}", replay.paymentId(), replay.status());
      return PaymentSubmission.replayed(toResponse(replay));
    }
    rejectIfInvalid(violations);

    PaymentCommand command = command(request);
    if (idempotencyKey == null) {
      // V1 compatibility: bank first, and persist only an explicit definitive outcome.
      DefinitiveOutcome outcome = toDefinitive(bankClient.process(request));
      return PaymentSubmission.created(
          toResponse(repository.saveDefinitive(merchantId, command, outcome)));
    }

    AdmissionResult admission = repository.admitAndClaim(merchantId, idempotencyKey, command);
    if (admission.kind() == AdmissionResult.Kind.CONFLICT) {
      throw conflict(admission.payment());
    }
    if (admission.kind() == AdmissionResult.Kind.EXISTING) {
      return PaymentSubmission.replayed(toResponse(admission.payment()));
    }

    // CREATED is the execution-ownership token. Existing state, including IN_FLIGHT, never is.
    LOG.info("Payment admitted and execution claimed payment_id={}",
        admission.payment().paymentId());
    return PaymentSubmission.created(toResponse(
        executeClaimed(admission.payment().paymentId(), request)));
  }

  private PaymentRecord executeClaimed(UUID paymentId, PostPaymentRequest request) {
    BankOutcome bankOutcome;
    try {
      bankOutcome = bankClient.process(request);
    } catch (GatewayException bankFailure) {
      PaymentRecord current = recordUnknown(paymentId, bankFailure);
      if (isDefinitive(current.status())) return current;
      throw bankFailure.withPayment(paymentId, current.status());
    } catch (RuntimeException unexpectedBankFailure) {
      recordUnknown(paymentId, unexpectedBankFailure);
      throw unexpectedBankFailure;
    }
    PaymentRecord completed = repository.complete(paymentId, toDefinitive(bankOutcome));
    LOG.info("Definitive bank outcome applied payment_id={} status={}",
        paymentId, completed.status());
    return completed;
  }

  private PaymentRecord recordUnknown(UUID paymentId, RuntimeException bankFailure) {
    try {
      PaymentRecord result = repository.markUnknown(paymentId);
      if (result.status() == PaymentStatus.UNKNOWN) {
        LOG.warn("Payment outcome uncertain payment_id={}", paymentId);
      }
      return result;
    } catch (RuntimeException localFailure) {
      if (localFailure != bankFailure) localFailure.addSuppressed(bankFailure);
      throw localFailure;
    }
  }

  private static DefinitiveOutcome toDefinitive(BankOutcome outcome) {
    return outcome == BankOutcome.AUTHORIZED
        ? DefinitiveOutcome.AUTHORIZED : DefinitiveOutcome.DECLINED;
  }

  private static boolean isDefinitive(PaymentStatus status) {
    return status == PaymentStatus.AUTHORIZED || status == PaymentStatus.DECLINED;
  }

  private static Set<ConstraintViolation<PostPaymentRequest>> structuralViolations(
      Set<ConstraintViolation<PostPaymentRequest>> violations) {
    return violations.stream()
        .filter(violation -> !violation.getConstraintDescriptor().getAnnotation()
            .annotationType().getSimpleName().equals("ValidExpiry"))
        .collect(java.util.stream.Collectors.toSet());
  }

  private static void validateKey(String idempotencyKey) {
    if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 255)) {
      throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must contain between 1 and 255 characters");
    }
  }

  private static GatewayException conflict(PaymentRecord payment) {
    LOG.warn("Idempotency conflict payment_id={}", payment.paymentId());
    return new GatewayException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
        "Idempotency-Key was already used for a different payment");
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

  private static PaymentResponse toResponse(PaymentRecord payment) {
    return new PaymentResponse(payment.paymentId(), payment.status(), payment.cardNumberLastFour(),
        payment.expiryMonth(), payment.expiryYear(), payment.currency(), payment.amount());
  }
}

