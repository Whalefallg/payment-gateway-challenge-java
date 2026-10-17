package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.YearMonth;

public class ValidExpiryValidator implements ConstraintValidator<ValidExpiry, PostPaymentRequest> {

  private final Clock clock;

  public ValidExpiryValidator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean isValid(PostPaymentRequest value, ConstraintValidatorContext context) {
    if (value == null || value.getExpiryMonth() == null || value.getExpiryYear() == null) {
      return true;
    }
    try {
      YearMonth expiry = YearMonth.of(value.getExpiryYear(), value.getExpiryMonth());
      if (expiry.isAfter(YearMonth.now(clock))) {
        return true;
      }
    } catch (DateTimeException ignored) {
      return true;
    }
    context.disableDefaultConstraintViolation();
    context.buildConstraintViolationWithTemplate("must be later than the current UTC month")
        .addPropertyNode("expiryYear")
        .addConstraintViolation();
    return false;
  }
}
