package com.checkout.payment.gateway.client;

import com.fasterxml.jackson.annotation.JsonProperty;

final class BankRequest {
  @JsonProperty("card_number")
  private final String cardNumber;
  @JsonProperty("expiry_date")
  private final String expiryDate;
  private final String currency;
  private final int amount;
  private final String cvv;

  BankRequest(String cardNumber, String expiryDate, String currency, int amount, String cvv) {
    this.cardNumber = cardNumber;
    this.expiryDate = expiryDate;
    this.currency = currency;
    this.amount = amount;
    this.cvv = cvv;
  }

  public String getCardNumber() { return cardNumber; }
  public String getExpiryDate() { return expiryDate; }
  public String getCurrency() { return currency; }
  public int getAmount() { return amount; }
  public String getCvv() { return cvv; }
}
