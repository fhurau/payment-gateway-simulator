package com.paymentgateway.notification.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Retry/DLQ classification per DESIGN.md §11, same pattern as payment-processor's
 * {@code KafkaErrorHandlerConfig}. Spring Boot auto-detects this single
 * {@code DefaultErrorHandler} bean and wires it into the auto-configured Kafka listener
 * container factory - no hand-rolled retry loop, no factory customization.
 */
@Configuration
public class KafkaErrorHandlerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // record.partition() is only safe because every topic here auto-creates with 1
        // partition; if source topics ever gain partitions, .dlq topics must match or this
        // resolver must map to partition 0.
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".dlq", record.partition()));

        // 1 initial attempt + 2 retries = 3 total deliveries, per §11's "start 1s, x2, max 3 attempts".
        var backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // Poison messages (deserialization failures) skip retries entirely - retrying a
        // malformed message can never succeed. Business outcomes never reach this handler:
        // NotificationService never throws for a normal payment.completed/payment.failed
        // envelope, only a dedupe-skip (§10 step 1's idiom, reused here), which returns
        // normally rather than throwing.
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        return errorHandler;
    }
}
