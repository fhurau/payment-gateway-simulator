package com.paymentgateway.apigateway.payment;

import com.paymentgateway.apigateway.validation.IsoCurrency;
import com.paymentgateway.apigateway.validation.ValidAmountScale;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

@ValidAmountScale
public record PaymentRequest(
        @NotBlank String fromAccount,
        @NotBlank String toAccount,
        @NotBlank String amount,
        @NotBlank @IsoCurrency String currency) {

    // Self-transfers are rejected per §4: they would net to zero while still writing two
    // ledger entries against one account - allowed nowhere, so refuse them at the edge.
    @AssertTrue(message = "SELF_TRANSFER_NOT_ALLOWED: fromAccount and toAccount must differ")
    public boolean isAccountsDistinct() {
        return fromAccount == null || !fromAccount.equals(toAccount);
    }
}
