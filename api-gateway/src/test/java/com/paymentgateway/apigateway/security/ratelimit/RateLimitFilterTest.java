package com.paymentgateway.apigateway.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.DelegatingServletOutputStream;

class RateLimitFilterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RateLimitProperties properties = new RateLimitProperties(2, 60);
    private final RateLimitFilter filter = new RateLimitFilter(redisTemplate, properties, new ObjectMapper());

    private HttpServletRequest request(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }

    @Test
    void requestsWithinTheLimitPassThrough() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:127.0.0.1:" + (System.currentTimeMillis() / 1000 / 60)))
                .thenReturn(1L);

        HttpServletRequest request = request("/payments");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void requestOverTheLimitIsRejectedWith429() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:127.0.0.1:" + (System.currentTimeMillis() / 1000 / 60)))
                .thenReturn(3L);

        HttpServletRequest request = request("/payments");
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new DelegatingServletOutputStream(body));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(429);
        verify(chain, never()).doFilter(request, response);
        assertThat(body.toString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void nonPaymentsRequestsAreNeverThrottled() throws Exception {
        HttpServletRequest request = request("/auth/token");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(redisTemplate, never()).opsForValue();
    }
}
