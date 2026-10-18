package com.checkout.payment.gateway.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.checkout.payment.gateway.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

class PaymentTransitionPolicyTest {

  private final PaymentTransitionPolicy policy = new PaymentTransitionPolicy();

  @Test
  void permitsTheExplicitLifecycle() {
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.READY, PaymentStatus.IN_FLIGHT));
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.READY, PaymentStatus.FAILED_NO_EFFECT));
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.IN_FLIGHT, PaymentStatus.AUTHORIZED));
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.IN_FLIGHT, PaymentStatus.DECLINED));
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.IN_FLIGHT, PaymentStatus.UNKNOWN));
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.UNKNOWN, PaymentStatus.AUTHORIZED));
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.UNKNOWN, PaymentStatus.DECLINED));
    assertDoesNotThrow(() -> policy.requireAllowed(PaymentStatus.UNKNOWN, PaymentStatus.FAILED_NO_EFFECT));
  }

  @Test
  void terminalOutcomesCannotBeOverwrittenAndUnknownCannotReturnReady() {
    assertThrows(IllegalStateException.class,
        () -> policy.requireAllowed(PaymentStatus.AUTHORIZED, PaymentStatus.DECLINED));
    assertThrows(IllegalStateException.class,
        () -> policy.requireAllowed(PaymentStatus.DECLINED, PaymentStatus.AUTHORIZED));
    assertThrows(IllegalStateException.class,
        () -> policy.requireAllowed(PaymentStatus.UNKNOWN, PaymentStatus.READY));
  }
}
