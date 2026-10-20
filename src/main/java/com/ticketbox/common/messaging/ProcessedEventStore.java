package com.ticketbox.common.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Chống xử lý trùng ở phía consumer (Kafka đảm bảo at-least-once: một tin nhắn có thể đến nhiều lần,
 * ví dụ consumer xử lý xong nhưng chết trước khi commit offset).
 * <p>
 * Cách dùng: trong CÙNG transaction với việc xử lý, gọi {@link #markProcessed} trước. Trả về false nghĩa là
 * đã xử lý rồi, bỏ qua. Nếu việc xử lý ném lỗi thì transaction rollback, dòng đánh dấu cũng mất,
 * lần giao lại sau sẽ xử lý lại. Hai bản sao chạy song song: bản đến sau bị chặn ở khóa chính
 * cho tới khi bản đầu commit, rồi nhận "đã xử lý".
 */
@Component
public class ProcessedEventStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedEventStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true nếu đây là lần đầu consumer này xử lý sự kiện */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(long eventId, String consumer) {
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, consumer, processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id, consumer) DO NOTHING""", eventId, consumer, now());
        return inserted == 1;
    }

    public int deleteProcessedBefore(Instant before) {
        return jdbc.update("DELETE FROM processed_events WHERE processed_at < ?",
                OffsetDateTime.ofInstant(before, ZoneOffset.UTC));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
