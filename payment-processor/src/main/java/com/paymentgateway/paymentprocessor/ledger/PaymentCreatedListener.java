package com.paymentgateway.paymentprocessor.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentCreatedListener {

    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    public PaymentCreatedListener(LedgerService ledgerService, ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.created")
    public void onPaymentCreated(String message) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        ledgerService.processPaymentCreated(envelope);
    }
}
