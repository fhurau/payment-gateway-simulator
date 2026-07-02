package com.paymentgateway.apigateway.validation;

import com.paymentgateway.apigateway.payment.PaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import java.util.Currency;

public class AmountScaleValidator implements ConstraintValidator<ValidAmountScale, PaymentRequest> {

    /** NUMERIC(19,4) = 15 integer digits max (§4 money rules). */
    public static final int MAX_INTEGER_DIGITS = 15;

    @Override
    public boolean isValid(PaymentRequest request, ConstraintValidatorContext context) {
        if (request.amount() == null || request.amount().isBlank()
                || request.currency() == null || request.currency().isBlank()) {
            return true; // let @NotBlank report these
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(request.amount());
        } catch (NumberFormatException e) {
            return violate(context, "amount must be a valid decimal number");
        }

        // Scientific notation ("1e100") parses to a negative scale, which slips past the
        // scale check below and past any naive length limit - require plain decimal form.
        if (request.amount().indexOf('e') >= 0 || request.amount().indexOf('E') >= 0) {
            return violate(context, "amount must be in plain decimal notation");
        }

        if (amount.signum() <= 0) {
            return violate(context, "amount must be positive");
        }

        // NUMERIC(19,4) holds at most 15 integer digits; anything larger would overflow at
        // insert time in payment-processor and take the infra retry/DLQ path instead of a 400.
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            return violate(context, "amount exceeds the maximum of " + MAX_INTEGER_DIGITS + " integer digits");
        }

        Currency currency;
        try {
            currency = Currency.getInstance(request.currency());
        } catch (IllegalArgumentException e) {
            return true; // @IsoCurrency on the field already reports this
        }

        int allowedScale = currency.getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > allowedScale) {
            return violate(context, "INVALID_AMOUNT_SCALE");
        }

        return true;
    }

    private boolean violate(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
