package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.validation.ValidExpiry;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@ValidExpiry
public class PostPaymentRequest {

  @JsonProperty("card_number")
  @NotNull(message = "is required")
  @Pattern(regexp = "[0-9]{14,19}", message = "must contain 14 to 19 ASCII digits")
  private String cardNumber;

  @JsonProperty("expiry_month")
  @NotNull(message = "is required")
  @Min(value = 1, message = "must be between 1 and 12")
  @Max(value = 12, message = "must be between 1 and 12")
  private Integer expiryMonth;

  @JsonProperty("expiry_year")
  @NotNull(message = "is required")
  @Min(value = 1000, message = "must be a four-digit year")
  @Max(value = 9999, message = "must be a four-digit year")
  private Integer expiryYear;

  @NotNull(message = "is required")
  @Pattern(regexp = "USD|GBP|EUR", message = "must be one of USD, GBP or EUR")
  private String currency;

  @NotNull(message = "is required")
  private Integer amount;

  @NotNull(message = "is required")
  @Pattern(regexp = "[0-9]{3,4}", message = "must contain 3 or 4 ASCII digits")
  private String cvv;

  public String getCardNumber() { return cardNumber; }
  public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }
  public Integer getExpiryMonth() { return expiryMonth; }
  public void setExpiryMonth(Integer expiryMonth) { this.expiryMonth = expiryMonth; }
  public Integer getExpiryYear() { return expiryYear; }
  public void setExpiryYear(Integer expiryYear) { this.expiryYear = expiryYear; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public Integer getAmount() { return amount; }
  public void setAmount(Integer amount) { this.amount = amount; }
  public String getCvv() { return cvv; }
  public void setCvv(String cvv) { this.cvv = cvv; }
}
