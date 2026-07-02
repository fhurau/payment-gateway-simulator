package com.paymentgateway.apigateway.security;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final DemoTokenService demoTokenService;

    public AuthController(DemoTokenService demoTokenService) {
        this.demoTokenService = demoTokenService;
    }

    public record TokenResponse(String token) {
    }

    @PostMapping("/auth/token")
    public TokenResponse issueToken() {
        return new TokenResponse(demoTokenService.mintToken());
    }
}
