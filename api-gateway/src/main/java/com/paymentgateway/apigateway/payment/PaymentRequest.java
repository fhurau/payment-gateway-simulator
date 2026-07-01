package com.paymentgateway.apigateway.payment;

import com.paymentgateway.apigateway.validation.IsoCurrency;
import com.paymentgateway.apigateway.validation.ValidAmountScale;
import jakarta.validation.constraints.NotBlank;

@ValidAmountScale
public record PaymentRequest(
        @NotBlank String fromAccount,
        @NotBlank String toAccount,
        @NotBlank String amount,
        @NotBlank @IsoCurrency String currency) {
}
