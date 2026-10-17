package com.checkout.payment.gateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankOutcome;
import com.checkout.payment.gateway.exception.GatewayException;
import com.checkout.payment.gateway.model.PaymentRecord;
import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentGatewayServiceTest {

  @Test
  void independentAuthorizedAndDeclinedPaymentsAreSanitizedAndStored() {
    BankClient bank = mock(BankClient.class);
    PaymentsRepository repository = new PaymentsRepository();
    PaymentGatewayService service = new PaymentGatewayService(repository, bank);
    PostPaymentRequest request = request();
    when(bank.process(request)).thenReturn(BankOutcome.AUTHORIZED, BankOutcome.DECLINED);

    PaymentResponse first = service.processPayment(request);
    PaymentResponse second = service.processPayment(request);

    assertNotEquals(first.id(), second.id());
    assertEquals("Authorized", first.status().getName());
    assertEquals("Declined", second.status().getName());
    assertEquals(2, repository.size());
    PaymentRecord stored = repository.get(first.id()).orElseThrow();
    assertEquals("0001", stored.cardNumberLastFour());
    assertEquals(first, service.getPaymentById(first.id()));
    org.junit.jupiter.api.Assertions.assertFalse(
        java.util.Arrays.stream(PaymentRecord.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase())
            .anyMatch(name -> name.equals("cardnumber") || name.equals("cvv")));
  }

  @Test
  void bankFailureDoesNotCreateRecord() {
    BankClient bank = mock(BankClient.class);
    PaymentsRepository repository = new PaymentsRepository();
    PaymentGatewayService service = new PaymentGatewayService(repository, bank);
    PostPaymentRequest request = request();
    when(bank.process(request)).thenThrow(new GatewayException(
        HttpStatus.SERVICE_UNAVAILABLE, "BANK_UNAVAILABLE", "Bank is unavailable"));

    assertThrows(GatewayException.class, () -> service.processPayment(request));
    assertEquals(0, repository.size());
  }

  private PostPaymentRequest request() {
    PostPaymentRequest request = new PostPaymentRequest();
    request.setCardNumber("2222405343240001");
    request.setExpiryMonth(12);
    request.setExpiryYear(2099);
    request.setCurrency("GBP");
    request.setAmount(1050);
    request.setCvv("007");
    return request;
  }
}
