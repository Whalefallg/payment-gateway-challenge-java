package com.checkout.payment.gateway.exception;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final String safeMessage;

  public GatewayException(HttpStatus status, String code, String safeMessage) {
    super(safeMessage, null, false, false);
    this.status = status;
    this.code = code;
    this.safeMessage = safeMessage;
  }

  public HttpStatus getStatus() { return status; }
  public String getCode() { return code; }
  public String getSafeMessage() { return safeMessage; }
}
