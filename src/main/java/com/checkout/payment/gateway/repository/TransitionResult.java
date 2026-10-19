package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.PaymentRecord;

public record TransitionResult(boolean applied, PaymentRecord payment) {}

