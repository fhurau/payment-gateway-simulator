package com.paymentgateway.apigateway.redis;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal circuit breaker shared by every Redis touchpoint on the request path
 * (idempotency fast path, rate limiter). Redis calls already fail open to their Postgres /
 * no-op fallbacks (§7), but each failed call still costs the full client timeout (~2s) on a
 * servlet thread - during a sustained outage that serializes into a thread-pool bottleneck.
 * After {@link #FAILURE_THRESHOLD} consecutive failures the breaker opens for
 * {@link #OPEN_MILLIS}: callers skip Redis entirely and go straight to their fallback. Once
 * the window elapses the next call probes Redis again; a success closes the breaker.
 *
 * ponytail: fixed threshold/cooldown, no half-open state machine, no per-command breakers -
 * upgrade to resilience4j if this ever needs tuning beyond two constants.
 */
@Component
public class RedisCircuitBreaker {

    static final int FAILURE_THRESHOLD = 3;
    static final long OPEN_MILLIS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntil;

    /** True while the breaker is open - skip Redis and use the fallback directly. */
    public boolean isOpen() {
        return System.currentTimeMillis() < openUntil;
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD && !isOpen()) {
            openUntil = System.currentTimeMillis() + OPEN_MILLIS;
            log.warn("Redis circuit breaker opened for {}ms after {} consecutive failures - "
                    + "request path degrades to Postgres/no-limit until Redis recovers (§7)",
                    OPEN_MILLIS, consecutiveFailures.get());
        }
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
    }
}
