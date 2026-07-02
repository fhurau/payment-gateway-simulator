package com.paymentgateway.paymentprocessor.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes unpublished {@code outbox} rows ({@code payment.completed}/{@code payment.failed})
 * to Kafka (§8). Same pattern as api-gateway's relay - see that class for the rationale,
 * including the per-row failure isolation and {@code failed_attempts} parking.
 */
@Component
public class OutboxRelay {

    static final int MAX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record OutboxRow(UUID id, UUID aggregateId, UUID eventId, String eventType,
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
                        + "FROM outbox WHERE published_at IS NULL AND failed_attempts < " + MAX_ATTEMPTS
                        + " ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new OutboxRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("aggregate_id")),
                        UUID.fromString(rs.getString("event_id")),
                        rs.getString("event_type"),
                        rs.getString("headers"),
                        rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class)));

        for (OutboxRow row : rows) {
            try {
                publish(row);
            } catch (Exception e) {
                recordFailure(row, e);
                continue;
            }
            jdbcTemplate.update("UPDATE outbox SET published_at = now() WHERE id = ?", row.id());
        }
    }

    private void recordFailure(OutboxRow row, Exception e) {
        Integer attempts = jdbcTemplate.queryForObject(
                "UPDATE outbox SET failed_attempts = failed_attempts + 1 WHERE id = ? RETURNING failed_attempts",
                Integer.class, row.id());
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            log.error("outbox row {} ({}) parked after {} failed publish attempts - "
                    + "see README's outbox recovery section", row.id(), row.eventType(), attempts, e);
        } else {
            log.warn("failed to publish outbox row {} ({}), attempt {}/{}",
                    row.id(), row.eventType(), attempts, MAX_ATTEMPTS, e);
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
