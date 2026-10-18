package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    PaymentStatus status,
    @JsonProperty("code") String errorCode,
    String message,
    @JsonProperty("request_id") String requestId,
    List<ErrorDetail> details,
    @JsonProperty("payment_id") UUID paymentId) {

  public ErrorResponse(PaymentStatus status, String errorCode, String message,
      String requestId, List<ErrorDetail> details) {
    this(status, errorCode, message, requestId, details, null);
  }

  public record ErrorDetail(String field, String message) {}
}
