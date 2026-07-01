package com.paymentgateway.apigateway.payment;

public record PaymentResponse(String paymentId, String status, String idempotencyKey) {
}
