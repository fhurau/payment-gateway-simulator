package com.paymentgateway.apigateway.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Service
public class DemoTokenService {

    private final JwtEncoder jwtEncoder;
    private final long expiryMinutes;

    public DemoTokenService(SecretKey jwtSecretKey, JwtProperties jwtProperties) {
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
        this.expiryMinutes = jwtProperties.expiryMinutes();
    }

    public String mintToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("api-gateway")
                .subject("demo-user")
                .issuedAt(now)
                .expiresAt(now.plus(expiryMinutes, ChronoUnit.MINUTES))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
