package com.paymentgateway.apigateway.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentgateway.apigateway.payment.PaymentRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AmountScaleValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void jpyWholeAmountIsValid() {
        var request = new PaymentRequest("acc-1", "acc-2", "1000", "JPY");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void jpyAmountWithDecimalsIsRejected() {
        var request = new PaymentRequest("acc-1", "acc-2", "1000.50", "JPY");
        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getMessage().equals("INVALID_AMOUNT_SCALE"));
    }

    @Test
    void usdTwoDecimalAmountIsValid() {
        var request = new PaymentRequest("acc-1", "acc-2", "10.50", "USD");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void usdThreeDecimalAmountIsRejected() {
        var request = new PaymentRequest("acc-1", "acc-2", "10.505", "USD");
        Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getMessage().equals("INVALID_AMOUNT_SCALE"));
    }

    @Test
    void kwdThreeDecimalAmountIsValid() {
        var request = new PaymentRequest("acc-1", "acc-2", "10.505", "KWD");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void nonPositiveAmountIsRejected() {
        var request = new PaymentRequest("acc-1", "acc-2", "0", "USD");
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void unknownCurrencyIsRejectedByIsoCurrencyConstraint() {
        var request = new PaymentRequest("acc-1", "acc-2", "10", "XXX_NOT_REAL");
        assertThat(validator.validate(request)).isNotEmpty();
    }
}
