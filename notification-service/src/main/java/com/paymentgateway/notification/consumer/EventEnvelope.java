package com.paymentgateway.notification.consumer;

import java.util.Map;

/** The event envelope shape from DESIGN.md §4, shared across all Kafka topics. */
public record EventEnvelope(
        String eventId,
        String eventType,
        String occurredAt,
        String correlationId,
        String paymentId,
        Map<String, String> payload) {
}
