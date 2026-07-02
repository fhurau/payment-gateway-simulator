package com.paymentgateway.paymentprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
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
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + ".dlq", record.partition()));

        // Cumulative DLQ count (§14's "DLQ depth" dashboard panel) - a Kafka consumer-lag-based
        // depth metric would need a JMX/Kafka exporter, an extra moving part this demo doesn't
        // otherwise need; a counter incremented at the point of dead-lettering is the honest,
        // zero-extra-infra proxy and is labeled as a count, not a lag/depth, in the dashboard.
        ConsumerRecordRecoverer countingRecoverer = (record, ex) -> {
            recoverer.accept(record, ex);
            meterRegistry.counter("payment_processor_dlq_messages_total", "topic", record.topic()).increment();
        };

        // 1 initial attempt + 2 retries = 3 total deliveries, per §11's "start 1s, x2, max 3 attempts".
        var backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(countingRecoverer, backOff);
        // Poison messages (deserialization failures) skip retries entirely - retrying a
        // malformed message can never succeed. Business failures never reach this handler
        // at all: LedgerService never throws for INSUFFICIENT_FUNDS/etc. (§10), so they
        // aren't a concern here by construction.
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        return errorHandler;
    }
}
