package com.paymentgateway.apigateway.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over the canonical request fields, used to detect Idempotency-Key reuse with a different body (§7). */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(PaymentRequest request) {
        String canonical = String.join("\0",
                request.fromAccount(), request.toAccount(), request.amount(), request.currency());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
