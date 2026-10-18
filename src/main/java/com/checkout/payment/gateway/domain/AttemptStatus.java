package com.checkout.payment.gateway.domain;

public enum AttemptStatus {
  READY,
  IN_FLIGHT,
  UNKNOWN,
  AUTHORIZED,
  DECLINED,
  FAILED_NO_EFFECT
}
