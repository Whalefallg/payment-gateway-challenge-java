package com.checkout.payment.gateway.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidExpiryValidator.class)
public @interface ValidExpiry {
  String message() default "must be later than the current UTC month";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
