package com.paymentgateway.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Thin wrapper over the JDK HTTP client for driving api-gateway's POST /payments (§6). */
public class PaymentClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String bearerToken;

    public PaymentClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.bearerToken = fetchDemoToken();
    }

    /** POST /payments is JWT-protected since Phase 7 (§14); mint a demo token once per client. */
    private String fetchDemoToken() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/auth/token"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            String body = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
            return body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        } catch (Exception e) {
            throw new RuntimeException("failed to mint demo JWT for E2E test", e);
        }
    }

    public HttpResponse<String> postPayment(String idempotencyKey, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/payments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> postPaymentWithoutIdempotencyKey(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/payments"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
