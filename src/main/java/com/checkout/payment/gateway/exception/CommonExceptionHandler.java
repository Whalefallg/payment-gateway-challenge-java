package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import com.checkout.payment.gateway.model.ErrorResponse.ErrorDetail;
import com.checkout.payment.gateway.enums.PaymentStatus;
import java.util.Comparator;
import java.util.List;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class CommonExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CommonExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> new ErrorDetail(toSnakeCase(error.getField()), error.getDefaultMessage()))
        .sorted(Comparator.comparing(ErrorDetail::field))
        .toList();
    return ResponseEntity.badRequest().body(new ErrorResponse(
        PaymentStatus.REJECTED, "INVALID_PAYMENT_REQUEST", "Payment request validation failed",
        requestId(), details));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest().body(new ErrorResponse(
        PaymentStatus.REJECTED, "INVALID_PAYMENT_REQUEST", "Payment request validation failed",
        requestId(), null));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleInvalidId(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.badRequest().body(error("INVALID_PAYMENT_ID", "Payment ID is invalid"));
  }

  @ExceptionHandler(GatewayException.class)
  public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex) {
    LOG.warn("Request failed category={}", ex.getCode());
    return ResponseEntity.status(ex.getStatus()).body(error(ex.getCode(), ex.getSafeMessage()));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .headers(ex.getHeaders())
        .body(error("METHOD_NOT_ALLOWED", "HTTP method is not supported"));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .headers(ex.getHeaders())
        .body(error("UNSUPPORTED_MEDIA_TYPE", "Content type is not supported"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    LOG.error("Unexpected application failure request_id={}", requestId());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(error("INTERNAL_ERROR", "An unexpected error occurred"));
  }

  private static ErrorResponse error(String code, String message) {
    return new ErrorResponse(null, code, message, requestId(), null);
  }

  private static String requestId() {
    return MDC.get("request_id");
  }

  private static String toSnakeCase(String value) {
    return value.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
  }
}
