package com.paymentgateway.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * §15's "insufficient-funds path": a business failure must never throw/retry/DLQ (§13) - it
 * commits as FAILED with a reason, and still gets a notification (§2).
 */
@Tag("e2e")
class InsufficientFundsE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentClient client = new PaymentClient(E2EEnvironment.apiGatewayBaseUrl());

    @Test
    void insufficientFundsFailsCleanlyWithNoLedgerEntries() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        // An amount no plausible accumulated balance from other tests could ever cover, so this
        // stays robust regardless of test execution order within the shared environment.
        String body = "{\"fromAccount\":\"jpy-low\",\"toAccount\":\"jpy-funded\","
                + "\"amount\":\"999999999999\",\"currency\":\"JPY\"}";

        var response = client.postPayment(idempotencyKey, body);
        assertEquals(201, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        String paymentId = json.get("paymentId").asText();

        Poller.await("payment " + paymentId + " to reach a terminal status", Duration.ofSeconds(20),
                () -> Db.paymentStatus(paymentId) != null);

        assertEquals("FAILED", Db.paymentStatus(paymentId));
        assertEquals("INSUFFICIENT_FUNDS", Db.failureReason(paymentId));
        assertEquals(0, Db.ledgerEntryCount(paymentId), "a business failure must never post ledger entries");

        Poller.await("a notification row for the failed payment " + paymentId, Duration.ofSeconds(20),
                () -> Db.notificationCount(paymentId) == 1);
    }
}
