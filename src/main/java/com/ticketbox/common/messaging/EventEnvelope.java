package com.ticketbox.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Định dạng chung của mọi tin nhắn trên Kafka.
 *
 * <pre>
 * {
 *   "eventId": 42,                         ← id trong outbox_events, consumer dùng để chống xử lý trùng
 *   "eventType": "BOOKING_CONFIRMED",
 *   "aggregateType": "BOOKING",
 *   "aggregateId": 7,
 *   "occurredAt": "2026-10-20T03:00:00Z",
 *   "payload": { ... }                     ← nội dung riêng của từng loại sự kiện (xem {@link EventTypes})
 * }
 * </pre>
 */
public record EventEnvelope(
        long eventId,
        String eventType,
        String aggregateType,
        long aggregateId,
        Instant occurredAt,
        JsonNode payload) {
}
