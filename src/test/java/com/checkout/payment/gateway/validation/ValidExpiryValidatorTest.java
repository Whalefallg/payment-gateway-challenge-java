package com.checkout.payment.gateway.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ValidExpiryValidatorTest {

  @Test
  void handlesDecemberToJanuaryRolloverUsingUtcClock() {
    Clock december = Clock.fixed(Instant.parse("2026-12-15T12:00:00Z"), ZoneOffset.UTC);
    PostPaymentRequest request = new PostPaymentRequest();
    request.setExpiryMonth(1);
    request.setExpiryYear(2027);

    assertTrue(new ValidExpiryValidator(december).isValid(request, null));
  }
}
