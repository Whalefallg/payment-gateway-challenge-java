package com.checkout.payment.gateway.domain;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Pure domain policy for the deliberately small authorization state machine. */
public final class PaymentTransitionPolicy {

  private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
      PaymentStatus.READY, EnumSet.of(PaymentStatus.IN_FLIGHT, PaymentStatus.FAILED_NO_EFFECT),
      PaymentStatus.IN_FLIGHT, EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.DECLINED,
          PaymentStatus.UNKNOWN),
      PaymentStatus.UNKNOWN, EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.DECLINED,
          PaymentStatus.FAILED_NO_EFFECT));

  public void requireAllowed(PaymentStatus current, PaymentStatus target) {
    if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
      throw new IllegalStateException("Transition from " + current + " to " + target + " is not allowed");
    }
  }

  public boolean isAllowed(PaymentStatus current, PaymentStatus target) {
    return ALLOWED.getOrDefault(current, Set.of()).contains(target);
  }
}
