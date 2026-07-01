package com.paymentgateway.notification.consumer;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code payment.completed}/{@code payment.failed} (§2) and records a stub
 * notification. Idempotent delivery via the same "insert into the dedupe table first, catch the
 * PK violation, skip" idiom used by payment-processor's ledger consumer (§10 step 1).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbcTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void processEvent(EventEnvelope envelope) {
        UUID eventId = UUID.fromString(envelope.eventId());

        try {
            jdbcTemplate.update("INSERT INTO consumed_events (event_id, event_type) VALUES (?, ?)",
                    eventId, envelope.eventType());
        } catch (DuplicateKeyException e) {
            // Redelivery of an already-processed event: skip, do not send twice (§2's
            // "idempotent delivery"). Postgres has already aborted this transaction on the
            // constraint violation, so nothing else may run here.
            log.info("skipping already-consumed event {}", eventId);
            return;
        }

        UUID paymentId = UUID.fromString(envelope.paymentId());
        String message = describe(envelope);
        log.info("stub notification: {}", message);

        jdbcTemplate.update(
                "INSERT INTO notifications (id, payment_id, event_id, channel, status) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), paymentId, eventId, "LOG", "SENT");
    }

    private String describe(EventEnvelope envelope) {
        Map<String, String> payload = envelope.payload();
        String base = "payment " + envelope.paymentId() + " " + envelope.eventType() + ": "
                + payload.get("amount") + " " + payload.get("currency") + " "
                + payload.get("fromAccount") + " -> " + payload.get("toAccount");
        if ("payment.failed".equals(envelope.eventType())) {
            return base + " (reason: " + payload.get("failureReason") + ")";
        }
        return base;
    }
}
