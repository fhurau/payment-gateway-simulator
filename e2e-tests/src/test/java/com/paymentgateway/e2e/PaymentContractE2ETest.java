package com.paymentgateway.e2e;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * §15's "contract test for the POST /payments schema" (§6) - exact field-shape assertions
 * against the real running api-gateway, not a mocked slice test.
 */
@Tag("e2e")
class PaymentContractE2ETest {

    private static final Set<String> SUCCESS_FIELDS = Set.of("paymentId", "status", "idempotencyKey");
    private static final Set<String> ERROR_FIELDS = Set.of("error", "message");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentClient client = new PaymentClient(E2EEnvironment.apiGatewayBaseUrl());

    @Test
    void createdResponseMatchesTheContract() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"55\",\"currency\":\"JPY\"}";

        var response = client.postPayment(idempotencyKey, body);
        assertEquals(201, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertFieldsExactly(SUCCESS_FIELDS, json);
        assertDoesNotThrow(() -> UUID.fromString(json.get("paymentId").asText()), "paymentId must be a UUID");
        assertEquals("PENDING", json.get("status").asText());
        assertEquals(idempotencyKey, json.get("idempotencyKey").asText());
    }

    @Test
    void replayedResponseMatchesTheSameContract() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"66\",\"currency\":\"JPY\"}";

        client.postPayment(idempotencyKey, body);
        var replay = client.postPayment(idempotencyKey, body);
        assertEquals(200, replay.statusCode());
        assertFieldsExactly(SUCCESS_FIELDS, objectMapper.readTree(replay.body()));
    }

    @Test
    void amountScaleViolationMatchesTheErrorContract() throws Exception {
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"10.50\",\"currency\":\"JPY\"}";
        var response = client.postPayment(UUID.randomUUID().toString(), body);
        assertEquals(400, response.statusCode());
        assertFieldsExactly(ERROR_FIELDS, objectMapper.readTree(response.body()));
    }

    @Test
    void missingIdempotencyKeyMatchesTheErrorContract() throws Exception {
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"10\",\"currency\":\"JPY\"}";
        var response = client.postPaymentWithoutIdempotencyKey(body);
        assertEquals(400, response.statusCode());
        assertFieldsExactly(ERROR_FIELDS, objectMapper.readTree(response.body()));
    }

    @Test
    void missingRequiredFieldMatchesTheErrorContract() throws Exception {
        String body = "{\"toAccount\":\"jpy-low\",\"amount\":\"10\",\"currency\":\"JPY\"}"; // no fromAccount
        var response = client.postPayment(UUID.randomUUID().toString(), body);
        assertEquals(400, response.statusCode());
        assertFieldsExactly(ERROR_FIELDS, objectMapper.readTree(response.body()));
    }

    @Test
    void conflictingReplayMatchesTheErrorContract() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"77\",\"currency\":\"JPY\"}";
        String differentBody = "{\"fromAccount\":\"jpy-funded\",\"toAccount\":\"jpy-low\",\"amount\":\"88\",\"currency\":\"JPY\"}";

        client.postPayment(idempotencyKey, body);
        var conflict = client.postPayment(idempotencyKey, differentBody);
        assertEquals(409, conflict.statusCode());
        assertFieldsExactly(ERROR_FIELDS, objectMapper.readTree(conflict.body()));
    }

    private void assertFieldsExactly(Set<String> expected, JsonNode json) {
        Set<String> actual = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(actual::add);
        assertTrue(actual.equals(expected), "expected fields " + expected + " but got " + actual);
    }
}
