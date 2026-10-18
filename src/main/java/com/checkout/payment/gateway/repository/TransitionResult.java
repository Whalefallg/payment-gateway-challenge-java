package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.domain.PaymentIntent;

public record TransitionResult(boolean applied, PaymentIntent payment) {}
