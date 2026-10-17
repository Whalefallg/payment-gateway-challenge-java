package com.checkout.payment.gateway.exception;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {
  private final HttpStatus httpStatus;
  private final String errorCode;
  private final String safeMessage;

  public GatewayException(HttpStatus httpStatus, String errorCode, String safeMessage) {
    // Do not retain upstream causes, which may contain sensitive payloads.
    // Stack traces are disabled for mapped failures, at the cost of diagnostic detail.
    super(safeMessage, null, false, false);
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
    this.safeMessage = safeMessage;
  }

  public HttpStatus getHttpStatus() { return httpStatus; }
  public String getErrorCode() { return errorCode; }
  public String getSafeMessage() { return safeMessage; }
}
