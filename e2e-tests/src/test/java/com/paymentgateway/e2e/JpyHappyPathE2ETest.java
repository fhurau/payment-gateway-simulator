package com.paymentgateway.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * §15's "JPY happy path": POST -> event flow (Kafka) -> ledger state -> notification, driven
 * against the real running stack (not mocks) via {@link E2EEnvironment}.
 */
@Tag("e2e")
class JpyHappyPathE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentClient client = new PaymentClient(E2EEnvironment.apiGatewayBaseUrl());

    @Test
    void happyPathProducesBalancedLedgerAndNotification() throws Exception {
        BigDecimal fromBalanceBefore = Db.accountBalance("jpy-funded");
        BigDecimal toBalanceBefore = Db.accountBalance("jpy-low");

        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"777\",\"currency\":\"JPY\"}";

        var response = client.postPayment(idempotencyKey, body);
        assertEquals(201, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        String paymentId = json.get("paymentId").asText();
        assertEquals("PENDING", json.get("status").asText());

        Poller.await("payment " + paymentId + " to reach a terminal status", Duration.ofSeconds(20),
                () -> Db.paymentStatus(paymentId) != null);
        assertEquals("COMPLETED", Db.paymentStatus(paymentId));

        assertEquals(2, Db.ledgerEntryCount(paymentId), "a COMPLETED payment must produce exactly 2 ledger entries");
        BigDecimal debit = Db.ledgerEntryAmount(paymentId, "DEBIT");
        BigDecimal credit = Db.ledgerEntryAmount(paymentId, "CREDIT");
        assertNotNull(debit);
        assertNotNull(credit);
        assertEquals(0, debit.compareTo(new BigDecimal("777")));
        assertEquals(0, credit.compareTo(new BigDecimal("777")));

        assertEquals(0, Db.accountBalance("jpy-funded").compareTo(fromBalanceBefore.subtract(new BigDecimal("777"))));
        assertEquals(0, Db.accountBalance("jpy-low").compareTo(toBalanceBefore.add(new BigDecimal("777"))));

        Poller.await("a notification row for payment " + paymentId, Duration.ofSeconds(20),
                () -> Db.notificationCount(paymentId) == 1);
    }
}
