package com.paymentgateway.apigateway.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.apigateway.web.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.GenericFilterBean;

/**
 * Fixed-window Redis counter, only guarding {@code /payments} (§14). Runs ahead of Spring
 * Security entirely (see FilterRegistrationBean order in RateLimitFilterConfig) so a
 * rate-limited caller gets 429 without paying for JWT verification.
 *
 * ponytail: keyed on client IP, not JWT subject - no verified subject exists this early in the
 * chain, and trusting a client-supplied one would let an attacker pick their own bucket. Switch
 * to subject-keyed limits once real user accounts exist.
 */
public class RateLimitFilter extends GenericFilterBean {

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redisTemplate, RateLimitProperties properties, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        if (!"/payments".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        long windowEpoch = System.currentTimeMillis() / 1000 / properties.windowSeconds();
        String key = "ratelimit:" + request.getRemoteAddr() + ":" + windowEpoch;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(properties.windowSeconds()));
        }

        if (count != null && count > properties.limit()) {
            response.setStatus(429);
            response.setContentType("application/json");
            objectMapper.writeValue(
                    response.getOutputStream(),
                    new ErrorResponse("RATE_LIMIT_EXCEEDED", "rate limit exceeded, retry later"));
            return;
        }

        chain.doFilter(request, response);
    }
}
