package com.paymentgateway.apigateway.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentgateway.apigateway.redis.RedisCircuitBreaker;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-layer idempotency store per DESIGN.md §7: Postgres ({@code idempotency_keys}) is the
 * source of truth, Redis ({@code idempotency:{key}}) is the fast path. Both layers store the
 * request hash alongside the response so a same-key-different-body request gets a 409 no
 * matter which layer serves the hit.
 */
@Component
public class IdempotencyStore {

    private static final Duration REDIS_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RedisCircuitBreaker circuitBreaker;

    public IdempotencyStore(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper, RedisCircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
    }

    public record StoredRecord(String requestHash, int status, String body) {
    }

    public Optional<StoredRecord> findInRedis(String idempotencyKey) {
        if (circuitBreaker.isOpen()) {
            return Optional.empty(); // known outage - go straight to Postgres, don't pay the timeout
        }
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(redisKey(idempotencyKey));
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            // Redis down - §7: in doubt, hit Postgres.
            circuitBreaker.recordFailure();
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            JsonNode envelope = objectMapper.readTree(raw);
            return Optional.of(new StoredRecord(
                    envelope.get("requestHash").asText(),
                    envelope.get("status").asInt(),
                    objectMapper.writeValueAsString(envelope.get("body"))));
        } catch (Exception e) {
            return Optional.empty(); // corrupt cache entry - fall through to Postgres
        }
    }

    public Optional<StoredRecord> findInPostgres(String idempotencyKey) {
        try {
            StoredRecord record = jdbcTemplate.queryForObject(
                    "SELECT request_hash, response_status, response_body::text AS body "
                            + "FROM idempotency_keys WHERE key = ?",
                    (rs, rowNum) -> new StoredRecord(
                            rs.getString("request_hash"), rs.getInt("response_status"), rs.getString("body")),
                    idempotencyKey);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Inserts the idempotency record and its paired outbox row in one transaction (§7, §8).
     * Runs in its own {@code REQUIRES_NEW} transaction so that, on a primary-key race, the
     * {@link org.springframework.dao.DuplicateKeyException} propagates to the caller with this
     * transaction already rolled back - Postgres aborts the whole transaction on the
     * constraint violation, so it must not be caught here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertNew(String idempotencyKey, UUID paymentId, String requestHash, int status, String responseBody,
            UUID outboxId, UUID eventId, String headersJson, String payloadJson) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_keys (key, payment_id, request_hash, response_status, response_body) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb)",
                idempotencyKey, paymentId, requestHash, status, responseBody);

        jdbcTemplate.update(
                "INSERT INTO outbox (id, aggregate_id, event_id, event_type, headers, payload) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                outboxId, paymentId, eventId, "payment.created", headersJson, payloadJson);
    }

    public void cacheInRedis(String idempotencyKey, String requestHash, int status, String body) {
        if (circuitBreaker.isOpen()) {
            return; // best-effort cache write - skip during a known outage
        }
        try {
            JsonNode bodyNode = objectMapper.readTree(body);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("requestHash", requestHash);
            envelope.put("status", status);
            envelope.set("body", bodyNode);
            redisTemplate.opsForValue().set(redisKey(idempotencyKey), objectMapper.writeValueAsString(envelope), REDIS_TTL);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            // Redis is a best-effort fast path; Postgres remains the source of truth.
            circuitBreaker.recordFailure();
        }
    }

    private String redisKey(String idempotencyKey) {
        return "idempotency:" + idempotencyKey;
    }
}
