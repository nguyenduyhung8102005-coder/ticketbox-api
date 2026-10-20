package com.ticketbox.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketbox.common.messaging.MessagingProperties;
import com.ticketbox.common.messaging.ProcessedEventStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * TRANSACTIONAL OUTBOX - phía đọc (polling publisher).
 * <p>
 * Mỗi lượt: khóa một lô sự kiện PENDING bằng {@code FOR UPDATE SKIP LOCKED} (chạy nhiều instance không gửi trùng
 * một sự kiện, không phải chờ nhau), gửi lần lượt lên Kafka và chờ xác nhận, rồi đánh dấu PUBLISHED.
 * <p>
 * Đảm bảo at-least-once: nếu gửi Kafka thành công nhưng chưa kịp cập nhật DB (ứng dụng chết), lượt sau gửi lại.
 * Vì vậy consumer BẮT BUỘC idempotent ({@link ProcessedEventStore}).
 * <p>
 * Gửi lỗi: tăng attempts, lùi {@code next_attempt_at} theo backoff lũy thừa, quá maxAttempts thì FAILED.
 * Dừng lô ngay ở sự kiện lỗi đầu tiên: Kafka sập thì các sự kiện sau cũng sẽ lỗi, và không để sự kiện
 * sau vượt lên trước sự kiện trước.
 * <p>
 * Phương án thay thế khi tải lớn: Debezium đọc WAL của PostgreSQL (CDC), không cần polling.
 */
@Slf4j
@Component
public class OutboxRelay {

    /** Giới hạn số lô mỗi lượt để một lượt không chạy quá lâu khi tồn đọng lớn. */
    private static final int MAX_BATCHES_PER_RUN = 10;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final OutboxMessageSender sender;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;
    private final ProcessedEventStore processedEventStore;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OutboxRelay(JdbcTemplate jdbc, OutboxMessageSender sender, ObjectMapper objectMapper,
                       MessagingProperties properties, ProcessedEventStore processedEventStore, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.processedEventStore = processedEventStore;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** @return số sự kiện đã gửi thành công trong lượt này */
    public int relayPending() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            BatchResult result = tx.execute(status -> relayOneBatch());
            if (result == null) {
                break;
            }
            total += result.published();
            if (result.failed() || result.fetched() < properties.outbox().batchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.debug("Outbox: đã đẩy {} sự kiện lên Kafka", total);
        }
        return total;
    }

    private BatchResult relayOneBatch() {
        Instant now = clock.instant();
        List<PendingEvent> events = jdbc.query("""
                SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, attempts, created_at
                  FROM outbox_events
                 WHERE status = 'PENDING' AND next_attempt_at <= ?
                 ORDER BY id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED""",
                OutboxRelay::mapRow, utc(now), properties.outbox().batchSize());

        int published = 0;
        for (PendingEvent event : events) {
            try {
                sender.send(event.aggregateType() + ":" + event.aggregateId(), event.eventType(), toMessage(event));
                jdbc.update("""
                        UPDATE outbox_events
                           SET status = 'PUBLISHED', published_at = ?, attempts = attempts + 1, last_error = NULL
                         WHERE id = ?""", utc(now), event.id());
                published++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                recordFailure(event, e, now);
                return new BatchResult(events.size(), published, true);
            }
        }
        return new BatchResult(events.size(), published, false);
    }

    private void recordFailure(PendingEvent event, Exception e, Instant now) {
        int attempts = event.attempts() + 1;
        boolean giveUp = attempts >= properties.outbox().maxAttempts();
        Instant nextAttempt = now.plus(backoff(attempts));
        jdbc.update("""
                UPDATE outbox_events
                   SET status = ?, attempts = ?, last_error = ?, next_attempt_at = ?
                 WHERE id = ?""",
                giveUp ? "FAILED" : "PENDING", attempts, truncate(e.toString()), utc(nextAttempt), event.id());
        if (giveUp) {
            log.error("Outbox: sự kiện {} ({}) gửi lỗi {} lần, chuyển FAILED, cần xử lý tay",
                    event.id(), event.eventType(), attempts, e);
        } else {
            log.warn("Outbox: gửi sự kiện {} lỗi lần {}, thử lại lúc {}: {}",
                    event.id(), attempts, nextAttempt, e.toString());
        }
    }

    /** 2s, 4s, 8s, ... tối đa maxBackoff. */
    Duration backoff(int attempts) {
        MessagingProperties.Outbox config = properties.outbox();
        long factor = 1L << Math.min(attempts - 1, 20);
        Duration delay = config.initialBackoff().multipliedBy(factor);
        return delay.compareTo(config.maxBackoff()) > 0 ? config.maxBackoff() : delay;
    }

    private String toMessage(PendingEvent event) throws JsonProcessingException {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", event.id());
        envelope.put("eventType", event.eventType());
        envelope.put("aggregateType", event.aggregateType());
        envelope.put("aggregateId", event.aggregateId());
        envelope.put("occurredAt", event.createdAt().toString());
        envelope.set("payload", objectMapper.readTree(event.payload()));
        return objectMapper.writeValueAsString(envelope);
    }

    /** Dọn sự kiện đã gửi và bản ghi chống trùng quá hạn lưu giữ. */
    public void cleanUp() {
        Instant now = clock.instant();
        Duration retention = properties.outbox().retention();
        int events = jdbc.update("DELETE FROM outbox_events WHERE status = 'PUBLISHED' AND published_at < ?",
                utc(now.minus(retention)));
        // Giữ lâu hơn outbox: tin nhắn có thể được giao lại muộn từ Kafka
        int processed = processedEventStore.deleteProcessedBefore(now.minus(retention.multipliedBy(2)));
        if (events + processed > 0) {
            log.info("Outbox: đã dọn {} sự kiện đã gửi, {} bản ghi processed_events", events, processed);
        }
    }

    private static PendingEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PendingEvent(
                rs.getLong("id"),
                rs.getString("aggregate_type"),
                rs.getLong("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private record PendingEvent(long id, String aggregateType, long aggregateId, String eventType, String payload,
                                int attempts, Instant createdAt) {
    }

    private record BatchResult(int fetched, int published, boolean failed) {
    }
}
