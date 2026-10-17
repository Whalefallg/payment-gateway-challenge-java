package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PaymentRecord;
import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private final PaymentsRepository paymentsRepository;
  private final BankClient bankClient;

  public PaymentGatewayService(PaymentsRepository paymentsRepository, BankClient bankClient) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
  }

  public PaymentResponse getPaymentById(UUID id) {
    return paymentsRepository.get(id)
        .map(PaymentGatewayService::toResponse)
        .orElseThrow(() -> new GatewayException(
            HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment was not found"));
  }

  public PaymentResponse processPayment(PostPaymentRequest request) {
    BankOutcome bankOutcome = bankClient.process(request);
    PaymentStatus status = bankOutcome == BankOutcome.AUTHORIZED
        ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
    PaymentRecord record = new PaymentRecord(
        UUID.randomUUID(), status,
        request.getCardNumber().substring(request.getCardNumber().length() - 4),
        request.getExpiryMonth(), request.getExpiryYear(), request.getCurrency(), request.getAmount());
    paymentsRepository.add(record);
    return toResponse(record);
  }

  private static PaymentResponse toResponse(PaymentRecord record) {
    return new PaymentResponse(record.id(), record.status(), record.cardNumberLastFour(),
        record.expiryMonth(), record.expiryYear(), record.currency(), record.amount());
  }
}
