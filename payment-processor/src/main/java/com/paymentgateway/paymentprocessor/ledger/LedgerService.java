package com.paymentgateway.paymentprocessor.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ACID double-entry ledger transaction from DESIGN.md §10. One transaction per
 * {@code payment.created} event: consumer-side de-dupe, locked account reads, business
 * validation (which never throws - a business failure is a normal, committed outcome), then
 * either the two balanced ledger entries or a recorded failure reason.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private record AccountRow(String accountId, BigDecimal balance, String currency) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LedgerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void processPaymentCreated(EventEnvelope envelope) {
        UUID eventId = UUID.fromString(envelope.eventId());
        UUID paymentId = UUID.fromString(envelope.paymentId());

        try {
            jdbcTemplate.update(
                    "INSERT INTO consumed_events (event_id, event_type, payment_id) VALUES (?, ?, ?)",
                    eventId, envelope.eventType(), paymentId);
        } catch (DuplicateKeyException e) {
            // Redelivery of an already-processed event (§10 step 1): skip, do not reprocess.
            // Postgres has already aborted this transaction on the constraint violation, so
            // nothing else may run here - returning lets the commit finalize as a no-op.
            log.info("skipping already-consumed event {}", eventId);
            return;
        }

        log.info("processing payment.created for paymentId={}", paymentId);

        Map<String, String> payload = envelope.payload();
        String fromAccount = payload.get("fromAccount");
        String toAccount = payload.get("toAccount");
        BigDecimal amount = new BigDecimal(payload.get("amount"));
        String currency = payload.get("currency");

        // Lock both accounts, in a deterministic order, to avoid deadlocking against a
        // concurrent payment moving money in the opposite direction between the same two accounts.
        List<AccountRow> accounts = jdbcTemplate.query(
                "SELECT account_id, balance, currency FROM accounts WHERE account_id IN (?, ?) "
                        + "ORDER BY account_id FOR UPDATE",
                (rs, rowNum) -> new AccountRow(
                        rs.getString("account_id"), rs.getBigDecimal("balance"), rs.getString("currency")),
                fromAccount, toAccount);
        Map<String, AccountRow> byAccountId = accounts.stream()
                .collect(Collectors.toMap(AccountRow::accountId, a -> a));

        AccountRow from = byAccountId.get(fromAccount);
        AccountRow to = byAccountId.get(toAccount);

        String failureReason = validate(from, to, currency, amount);
        if (failureReason != null) {
            insertPayment(paymentId, "FAILED", fromAccount, toAccount, amount, currency, failureReason);
            writeOutbox(paymentId, "payment.failed", envelope.correlationId(),
                    Map.of("fromAccount", fromAccount, "toAccount", toAccount, "amount", amount.toPlainString(),
                            "currency", currency, "failureReason", failureReason));
            return;
        }

        UUID debitEntryId = UUID.randomUUID();
        UUID creditEntryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, payment_id, account_id, direction, amount, currency) "
                        + "VALUES (?, ?, ?, 'DEBIT', ?, ?)",
                debitEntryId, paymentId, fromAccount, amount, currency);
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, payment_id, account_id, direction, amount, currency) "
                        + "VALUES (?, ?, ?, 'CREDIT', ?, ?)",
                creditEntryId, paymentId, toAccount, amount, currency);
        jdbcTemplate.update("UPDATE accounts SET balance = balance - ?, updated_at = now() WHERE account_id = ?",
                amount, fromAccount);
        jdbcTemplate.update("UPDATE accounts SET balance = balance + ?, updated_at = now() WHERE account_id = ?",
                amount, toAccount);

        insertPayment(paymentId, "COMPLETED", fromAccount, toAccount, amount, currency, null);
        writeOutbox(paymentId, "payment.completed", envelope.correlationId(),
                Map.of("fromAccount", fromAccount, "toAccount", toAccount, "amount", amount.toPlainString(),
                        "currency", currency, "debitEntryId", debitEntryId.toString(),
                        "creditEntryId", creditEntryId.toString()));
    }

    private String validate(AccountRow from, AccountRow to, String currency, BigDecimal amount) {
        if (from == null || to == null) {
            return "ACCOUNT_NOT_FOUND";
        }
        if (!from.currency().equals(currency) || !to.currency().equals(currency)) {
            return "CURRENCY_MISMATCH";
        }
        if (from.balance().compareTo(amount) < 0) {
            return "INSUFFICIENT_FUNDS";
        }
        return null;
    }

    private void insertPayment(UUID paymentId, String status, String fromAccount, String toAccount,
            BigDecimal amount, String currency, String failureReason) {
        jdbcTemplate.update(
                "INSERT INTO payments (id, status, from_account, to_account, amount, currency, failure_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                paymentId, status, fromAccount, toAccount, amount, currency, failureReason);
    }

    private void writeOutbox(UUID paymentId, String eventType, String correlationId, Map<String, String> payload) {
        try {
            String headersJson = objectMapper.writeValueAsString(Map.of("correlationId", correlationId));
            String payloadJson = objectMapper.writeValueAsString(payload);
            jdbcTemplate.update(
                    "INSERT INTO outbox (id, aggregate_id, event_id, event_type, headers, payload) "
                            + "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                    UUID.randomUUID(), paymentId, UUID.randomUUID(), eventType, headersJson, payloadJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload for payment " + paymentId, e);
        }
    }
}
