package com.paymentgateway.paymentprocessor.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes unpublished {@code outbox} rows ({@code payment.completed}/{@code payment.failed})
 * to Kafka (§8). Same pattern as api-gateway's relay - see that class for the full rationale:
 * row-intrinsic failures count toward parking, systemic (broker-unreachable) failures back off
 * and retry on the next poll without counting, sends are time-bounded, and parked rows are
 * exposed via the {@code outbox_parked_rows} gauge.
 */
@Component
public class OutboxRelay {

    static final int MAX_ATTEMPTS = 5;
    static final long SEND_TIMEOUT_SECONDS = 10;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record OutboxRow(UUID id, UUID aggregateId, UUID eventId, String eventType,
            String headersJson, String payloadJson, OffsetDateTime createdAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(JdbcTemplate jdbcTemplate, KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        Gauge.builder("outbox_parked_rows", () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL AND failed_attempts >= "
                                + MAX_ATTEMPTS, Integer.class))
                .description("outbox rows parked after repeated row-intrinsic publish failures (§8)")
                .register(meterRegistry);
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
            ProducerRecord<String, String> record;
            try {
                record = buildRecord(row);
            } catch (Exception e) {
                recordRowFailure(row, e); // the row's own data is bad - no send can ever succeed
                continue;
            }

            try {
                kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof ExecutionException && e.getCause() instanceof RecordTooLargeException) {
                    recordRowFailure(row, e); // broker rejects this specific record - row-intrinsic
                    continue;
                }
                log.warn("Kafka send failed for outbox row {} ({}) - treating as systemic, "
                        + "not counting toward parking; retrying batch on next poll",
                        row.id(), row.eventType(), e);
                break;
            }
            jdbcTemplate.update("UPDATE outbox SET published_at = now() WHERE id = ?", row.id());
        }
    }

    private void recordRowFailure(OutboxRow row, Exception e) {
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

    private ProducerRecord<String, String> buildRecord(OutboxRow row) throws Exception {
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
        return record;
    }
}
