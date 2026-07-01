package com.paymentgateway.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** §15's "duplicate Idempotency-Key path" (§7). */
@Tag("e2e")
class DuplicateIdempotencyKeyE2ETest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentClient client = new PaymentClient(E2EEnvironment.apiGatewayBaseUrl());

    @Test
    void sameKeySameBodyReplaysTheOriginalResponse() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"111\",\"currency\":\"JPY\"}";

        var first = client.postPayment(idempotencyKey, body);
        assertEquals(201, first.statusCode());

        var replay = client.postPayment(idempotencyKey, body);
        assertEquals(200, replay.statusCode());

        JsonNode firstJson = objectMapper.readTree(first.body());
        JsonNode replayJson = objectMapper.readTree(replay.body());
        assertEquals(firstJson.get("paymentId").asText(), replayJson.get("paymentId").asText());
        assertEquals(firstJson.get("idempotencyKey").asText(), replayJson.get("idempotencyKey").asText());

        assertEquals(1, Db.idempotencyKeyRowCount(idempotencyKey), "a replay must never create a second record");
    }

    @Test
    void sameKeyDifferentBodyConflicts() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"222\",\"currency\":\"JPY\"}";
        String differentBody = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"333\",\"currency\":\"JPY\"}";

        var first = client.postPayment(idempotencyKey, body);
        assertEquals(201, first.statusCode());

        var conflict = client.postPayment(idempotencyKey, differentBody);
        assertEquals(409, conflict.statusCode());

        assertEquals(1, Db.idempotencyKeyRowCount(idempotencyKey), "the conflicting request must not be processed");
    }
}
