package com.paymentgateway.apigateway.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a request's {@code amount} string is a positive decimal whose
 * scale does not exceed its {@code currency}'s ISO-4217 minor-unit exponent
 * (e.g. JPY = 0 decimals, USD = 2). Class-level because it needs both fields.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AmountScaleValidator.class)
public @interface ValidAmountScale {

    String message() default "INVALID_AMOUNT_SCALE";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
