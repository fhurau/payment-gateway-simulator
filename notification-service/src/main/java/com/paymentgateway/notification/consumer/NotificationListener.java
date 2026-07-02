package com.paymentgateway.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {

    private static final String MDC_KEY = "correlationId";

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationListener(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.completed", "payment.failed"})
    public void onPaymentOutcome(String message) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        MDC.put(MDC_KEY, envelope.correlationId());
        try {
            notificationService.processEvent(envelope);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
