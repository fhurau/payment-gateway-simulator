package com.paymentgateway.apigateway.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentgateway.apigateway.outbox.OutboxRelay.OutboxRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRelayTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxRelay relay =
            new OutboxRelay(jdbcTemplate, kafkaTemplate, new ObjectMapper(), new SimpleMeterRegistry());

    private static OutboxRow row(UUID id, String headersJson) {
        return new OutboxRow(id, UUID.randomUUID(), UUID.randomUUID(), "payment.created",
                headersJson, "{\"amount\":\"1000\"}", OffsetDateTime.now());
    }

    private static OutboxRow healthyRow(UUID id) {
        return row(id, "{\"correlationId\":\"" + UUID.randomUUID() + "\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedRowCountsTowardParkingAndDoesNotBlockSubsequentRows() {
        UUID poisonId = UUID.randomUUID();
        UUID healthyId = UUID.randomUUID();
        // Poison first in created_at order - the historical head-of-line-blocking case:
        // headers JSON has no correlationId, so buildRecord() throws (row-intrinsic).
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(row(poisonId, "{}"), healthyRow(healthyId)));
        when(jdbcTemplate.queryForObject(contains("failed_attempts + 1"), eq(Integer.class), eq(poisonId)))
                .thenReturn(1);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relay();

        // The healthy row behind the poison one is still published and marked.
        verify(kafkaTemplate).send(any(ProducerRecord.class));
        verify(jdbcTemplate).update("UPDATE outbox SET published_at = now() WHERE id = ?", healthyId);
        // The poison row is never marked published; its failure counter is incremented instead.
        verify(jdbcTemplate, never()).update("UPDATE outbox SET published_at = now() WHERE id = ?", poisonId);
        verify(jdbcTemplate).queryForObject(contains("failed_attempts + 1"), eq(Integer.class), eq(poisonId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void brokerOutageDoesNotCountTowardParkingAndStopsTheBatch() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(healthyRow(firstId), healthyRow(secondId)));
        // Systemic failure: the send fails with a connectivity-class error, not a row problem.
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new org.apache.kafka.common.errors.TimeoutException("broker unreachable")));

        relay.relay();

        // No parking progress for either row, nothing marked published, and the batch stopped
        // after the first failure instead of serializing timeouts for the rest.
        verify(jdbcTemplate, never()).queryForObject(contains("failed_attempts + 1"), eq(Integer.class), any());
        verify(jdbcTemplate, never()).update(contains("published_at"), any(UUID.class));
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordTooLargeIsRowIntrinsicAndCountsTowardParking() {
        UUID oversizedId = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(healthyRow(oversizedId)));
        when(jdbcTemplate.queryForObject(contains("failed_attempts + 1"), eq(Integer.class), eq(oversizedId)))
                .thenReturn(1);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too big")));

        relay.relay();

        verify(jdbcTemplate).queryForObject(contains("failed_attempts + 1"), eq(Integer.class), eq(oversizedId));
        verify(jdbcTemplate, never()).update(contains("published_at"), any(UUID.class));
    }
}
