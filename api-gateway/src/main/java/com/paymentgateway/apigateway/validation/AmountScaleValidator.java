package com.paymentgateway.apigateway.validation;

import com.paymentgateway.apigateway.payment.PaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import java.util.Currency;

public class AmountScaleValidator implements ConstraintValidator<ValidAmountScale, PaymentRequest> {

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

        if (amount.signum() <= 0) {
            return violate(context, "amount must be positive");
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
