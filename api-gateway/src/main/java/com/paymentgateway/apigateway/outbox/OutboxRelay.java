package com.paymentgateway.apigateway.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes unpublished {@code outbox} rows to Kafka (§8). Runs its own transaction per batch,
 * holding row locks via {@code FOR UPDATE SKIP LOCKED} so a slow send never blocks other rows and
 * concurrent relay instances never double-claim the same row. At-least-once by design: if the
 * process dies between a successful send and marking {@code published_at}, the row is re-sent on
 * the next poll - duplicates are absorbed by consumer de-dupe (§10 step 1).
 */
@Component
public class OutboxRelay {

    private record OutboxRow(UUID id, UUID aggregateId, UUID eventId, String eventType,
            String headersJson, String payloadJson, OffsetDateTime createdAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(JdbcTemplate jdbcTemplate, KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<OutboxRow> rows = jdbcTemplate.query(
                "SELECT id, aggregate_id, event_id, event_type, headers::text AS headers, "
                        + "payload::text AS payload, created_at "
                        + "FROM outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new OutboxRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("aggregate_id")),
                        UUID.fromString(rs.getString("event_id")),
                        rs.getString("event_type"),
                        rs.getString("headers"),
                        rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class)));

        for (OutboxRow row : rows) {
            publish(row);
            jdbcTemplate.update("UPDATE outbox SET published_at = now() WHERE id = ?", row.id());
        }
    }

    private void publish(OutboxRow row) {
        try {
            String correlationId = objectMapper.readTree(row.headersJson()).get("correlationId").asText();

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("eventId", row.eventId().toString());
            envelope.put("eventType", row.eventType());
            envelope.put("occurredAt", row.createdAt().toInstant().toString());
            envelope.put("correlationId", correlationId);
            envelope.put("paymentId", row.aggregateId().toString());
            JsonNode payload = objectMapper.readTree(row.payloadJson());
            envelope.set("payload", payload);

            ProducerRecord<String, String> record = new ProducerRecord<>(
                    row.eventType(), row.aggregateId().toString(), objectMapper.writeValueAsString(envelope));
            record.headers().add("correlationId", correlationId.getBytes());

            kafkaTemplate.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish outbox row " + row.id(), e);
        }
    }
}
