package com.paymentgateway.apigateway.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.apigateway.redis.RedisCircuitBreaker;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            StringRedisTemplate redisTemplate, RateLimitProperties properties, ObjectMapper objectMapper,
            RedisCircuitBreaker circuitBreaker) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(redisTemplate, properties, objectMapper, circuitBreaker));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 10);
        return registration;
    }
}
