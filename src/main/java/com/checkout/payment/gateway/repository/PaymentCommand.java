package com.checkout.payment.gateway.repository;

public record PaymentCommand(
    String requestFingerprint,
    int amount,
    String currency,
    String cardNumberLastFour,
    int expiryMonth,
    int expiryYear) {}
