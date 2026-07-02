package com.paymentgateway.apigateway.outbox;

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
 * Publishes unpublished {@code outbox} rows to Kafka (§8). Runs its own transaction per batch,
 * holding row locks via {@code FOR UPDATE SKIP LOCKED} so concurrent relay instances never
 * double-claim the same row.
 *
 * <p>Failure handling distinguishes two classes (§8):
 * <ul>
 * <li><b>Row-intrinsic</b> (the record can't be built from the row's data, or the broker
 * rejects it as too large): counts toward {@link #MAX_ATTEMPTS}; at the cap the row is
 * <b>parked</b> - excluded from the poll - for manual recovery (see the README's outbox
 * recovery section). One poison row never blocks the rows behind it.</li>
 * <li><b>Systemic</b> (broker unreachable, send timeout): does <b>not</b> count - parking the
 * whole backlog because Kafka is down would turn a transient outage into permanent event
 * loss. The batch stops early (retrying the remaining rows now would just serialize more
 * timeouts inside one long transaction) and the next 500ms poll retries from the front.</li>
 * </ul>
 *
 * <p>Sends are bounded at {@link #SEND_TIMEOUT_SECONDS}s so an outage can't hold the batch
 * transaction and its row locks for the producer's default multi-minute delivery timeout.
 * The {@code outbox_parked_rows} gauge makes parked rows visible on the dashboard rather than
 * only in per-row log lines. At-least-once by design: if the process dies between a
 * successful send and marking {@code published_at}, the row is re-sent on the next poll -
 * duplicates are absorbed by consumer de-dupe (§10 step 1).
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
