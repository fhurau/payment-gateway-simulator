package com.paymentgateway.e2e;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

/** Polls until a condition holds - processing after POST /payments is asynchronous (Kafka). */
public final class Poller {

    private Poller() {
    }

    public static void await(String description, Duration timeout, Callable<Boolean> condition) {
        Instant deadline = Instant.now().plus(timeout);
        Exception lastError = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
            } catch (Exception e) {
                lastError = e;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("timed out waiting for: " + description, lastError);
    }
}
