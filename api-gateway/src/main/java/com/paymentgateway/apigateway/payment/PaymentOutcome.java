package com.paymentgateway.apigateway.payment;

public record PaymentOutcome(int httpStatus, PaymentResponse body) {
}
