package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    PaymentStatus status,
    String code,
    String message,
    @JsonProperty("request_id") String requestId,
    List<ErrorDetail> details) {

  public record ErrorDetail(String field, String message) {}
}
