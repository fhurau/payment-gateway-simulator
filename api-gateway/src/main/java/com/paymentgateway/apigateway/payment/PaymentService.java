package com.paymentgateway.apigateway.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.apigateway.payment.IdempotencyStore.StoredRecord;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public PaymentService(IdempotencyStore idempotencyStore, ObjectMapper objectMapper) {
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    public PaymentOutcome initiate(PaymentRequest request, String idempotencyKey) {
        String requestHash = RequestHasher.hash(request);

        var redisHit = idempotencyStore.findInRedis(idempotencyKey);
        if (redisHit.isPresent()) {
            return toOutcome(redisHit.get(), requestHash, idempotencyKey);
        }

        var postgresHit = idempotencyStore.findInPostgres(idempotencyKey);
        if (postgresHit.isPresent()) {
            StoredRecord stored = postgresHit.get();
            idempotencyStore.cacheInRedis(idempotencyKey, stored.requestHash(), stored.status(), stored.body());
            return toOutcome(stored, requestHash, idempotencyKey);
        }

        return createNew(request, idempotencyKey, requestHash);
    }

    private PaymentOutcome createNew(PaymentRequest request, String idempotencyKey, String requestHash) {
        UUID paymentId = UUID.randomUUID();
        PaymentResponse responseBody = new PaymentResponse(paymentId.toString(), "PENDING", idempotencyKey);

        try {
            String responseBodyJson = objectMapper.writeValueAsString(responseBody);
            String headersJson = objectMapper.writeValueAsString(Map.of("correlationId", UUID.randomUUID().toString()));
            String payloadJson = objectMapper.writeValueAsString(Map.of(
                    "fromAccount", request.fromAccount(),
                    "toAccount", request.toAccount(),
                    "amount", request.amount(),
                    "currency", request.currency()));

            idempotencyStore.insertNew(idempotencyKey, paymentId, requestHash, 201, responseBodyJson,
                    UUID.randomUUID(), UUID.randomUUID(), headersJson, payloadJson);

            idempotencyStore.cacheInRedis(idempotencyKey, requestHash, 201, responseBodyJson);
            return new PaymentOutcome(201, responseBody);
        } catch (DuplicateKeyException e) {
            // Lost the race for this key (§7): the winner has already committed, re-read its row.
            StoredRecord winner = idempotencyStore.findInPostgres(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency key '" + idempotencyKey + "' vanished after a constraint violation"));
            idempotencyStore.cacheInRedis(idempotencyKey, winner.requestHash(), winner.status(), winner.body());
            return toOutcome(winner, requestHash, idempotencyKey);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize payment payload", e);
        }
    }

    private PaymentOutcome toOutcome(StoredRecord stored, String requestHash, String idempotencyKey) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        try {
            PaymentResponse body = objectMapper.readValue(stored.body(), PaymentResponse.class);
            return new PaymentOutcome(200, body);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
