package com.paymentgateway.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {

    private static final String MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationListener(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {"payment.completed", "payment.failed"})
    public void onPaymentOutcome(String message,
            @Header(name = "correlationId", required = false) byte[] correlationIdHeader) throws Exception {
        // §4/§14: the Kafka header copy of correlationId survives even when the body can't be
        // parsed, so MDC is populated *before* deserialization - a poison message's retry/DLQ
        // log lines stay traceable to the originating request.
        if (correlationIdHeader != null) {
            MDC.put(MDC_KEY, new String(correlationIdHeader, StandardCharsets.UTF_8));
        }
        try {
            EventEnvelope envelope;
            try {
                envelope = objectMapper.readValue(message, EventEnvelope.class);
            } catch (JsonProcessingException e) {
                log.error("failed to deserialize payment outcome message; handing to retry/DLQ", e);
                throw e;
            }
            if (correlationIdHeader == null) {
                MDC.put(MDC_KEY, envelope.correlationId()); // header missing (e.g. manual replay)
            }
            notificationService.processEvent(envelope);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
