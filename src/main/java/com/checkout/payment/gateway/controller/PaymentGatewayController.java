package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import com.checkout.payment.gateway.service.PaymentSubmission;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentGatewayController {

  private final PaymentGatewayService paymentGatewayService;

  public PaymentGatewayController(PaymentGatewayService paymentGatewayService) {
    this.paymentGatewayService = paymentGatewayService;
  }

  @PostMapping("/payments")
  public ResponseEntity<PaymentResponse> processPayment(
      @RequestBody PostPaymentRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Merchant-ID", defaultValue = PaymentGatewayService.DEFAULT_MERCHANT)
      String merchantId) {
    PaymentSubmission submission = paymentGatewayService.submitPayment(
        request, idempotencyKey, merchantId);
    PaymentResponse response = submission.payment();
    URI location = URI.create("/payments/" + response.id());
    if (submission.kind() == PaymentSubmission.Kind.CREATED) {
      return ResponseEntity.created(location).body(response);
    }
    if (response.status() == PaymentStatus.IN_FLIGHT) {
      return ResponseEntity.accepted().location(location).body(response);
    }
    return ResponseEntity.ok().location(location).body(response);
  }

  @GetMapping("/payments/{id}")
  public ResponseEntity<PaymentResponse> getPaymentById(@PathVariable UUID id) {
    return ResponseEntity.ok(paymentGatewayService.getPaymentById(id));
  }
}
