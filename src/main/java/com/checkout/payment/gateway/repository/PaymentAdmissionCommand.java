package com.checkout.payment.gateway.repository;

public record PaymentAdmissionCommand(
    String requestFingerprint,
    int amount,
    String currency,
    String cardNumberLastFour,
    int expiryMonth,
    int expiryYear) {}
