package com.paymentgateway.paymentprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Retry/DLQ classification per DESIGN.md §11. Spring Boot auto-detects this single
 * {@code DefaultErrorHandler} bean and wires it into the auto-configured Kafka listener
 * container factory - no hand-rolled retry loop, no factory customization.
 */
@Configuration
public class KafkaErrorHandlerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".dlq", record.partition()));

        // 1 initial attempt + 2 retries = 3 total deliveries, per §11's "start 1s, x2, max 3 attempts".
        var backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // Poison messages (deserialization failures) skip retries entirely - retrying a
        // malformed message can never succeed. Business failures never reach this handler
        // at all: LedgerService never throws for INSUFFICIENT_FUNDS/etc. (§10), so they
        // aren't a concern here by construction.
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        return errorHandler;
    }
}
