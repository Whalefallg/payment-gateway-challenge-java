package com.checkout.payment.gateway.exception;

import org.springframework.http.HttpStatus;
import com.checkout.payment.gateway.enums.PaymentStatus;
import java.util.UUID;

public class GatewayException extends RuntimeException {
  private final HttpStatus httpStatus;
  private final String errorCode;
  private final String safeMessage;
  private final UUID paymentId;
  private final PaymentStatus paymentStatus;

  public GatewayException(HttpStatus httpStatus, String errorCode, String safeMessage) {
    this(httpStatus, errorCode, safeMessage, null, null);
  }

  private GatewayException(HttpStatus httpStatus, String errorCode, String safeMessage,
      UUID paymentId, PaymentStatus paymentStatus) {
    // Do not retain upstream causes, which may contain sensitive payloads.
    // Stack traces are disabled for mapped failures, at the cost of diagnostic detail.
    super(safeMessage, null, false, false);
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
    this.safeMessage = safeMessage;
    this.paymentId = paymentId;
    this.paymentStatus = paymentStatus;
  }

  public GatewayException withPayment(UUID id, PaymentStatus status) {
    return new GatewayException(httpStatus, errorCode, safeMessage, id, status);
  }

  public HttpStatus getHttpStatus() { return httpStatus; }
  public String getErrorCode() { return errorCode; }
  public String getSafeMessage() { return safeMessage; }
  public UUID getPaymentId() { return paymentId; }
  public PaymentStatus getPaymentStatus() { return paymentStatus; }
}
