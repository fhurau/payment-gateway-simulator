package com.paymentgateway.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class DemoTokenServiceTest {

    private final SecretKeySpec key =
            new SecretKeySpec("test-secret-at-least-32-bytes-long-xx".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final DemoTokenService demoTokenService =
            new DemoTokenService(key, new JwtProperties("unused", 15));

    @Test
    void mintedTokenDecodesWithTheSameKeyAndHasExpectedClaims() {
        String token = demoTokenService.mintToken();

        Jwt decoded = NimbusJwtDecoder.withSecretKey(key).build().decode(token);

        assertThat(decoded.getSubject()).isEqualTo("demo-user");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("api-gateway");
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
    }
}
