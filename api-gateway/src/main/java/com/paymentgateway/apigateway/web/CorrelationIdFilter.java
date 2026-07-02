package com.paymentgateway.apigateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

/**
 * Generates the correlationId for every request (§14) and puts it in MDC so every log line for
 * this request - and, via {@code PaymentService}, every Kafka event this request causes - carries
 * the same ID. Runs first ({@code HIGHEST_PRECEDENCE}) so it wraps Spring Security and the
 * rate limiter too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends GenericFilterBean {

    public static final String MDC_KEY = "correlationId";
    private static final String RESPONSE_HEADER = "X-Correlation-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String correlationId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        ((HttpServletResponse) response).setHeader(RESPONSE_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
