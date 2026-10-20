package com.ticketbox.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * TRANSACTIONAL OUTBOX - phía ghi.
 * <p>
 * Thay vì gửi thẳng lên Kafka (có thể lệch với DB: DB commit mà Kafka lỗi thì mất email; gửi Kafka rồi DB
 * rollback thì khách nhận email cho đơn không tồn tại), sự kiện được INSERT vào bảng {@code outbox_events}
 * trong CÙNG transaction với thay đổi nghiệp vụ. Hai việc cùng commit hoặc cùng rollback.
 * {@link OutboxRelay} sẽ đẩy lên Kafka sau.
 * <p>
 * {@code Propagation.MANDATORY}: gọi ngoài transaction là lỗi lập trình, ném exception ngay
 * thay vì lặng lẽ ghi sự kiện tách rời khỏi nghiệp vụ.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** @return id của sự kiện (cũng là eventId consumer nhận được) */
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(String aggregateType, long aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Không serialize được payload sự kiện " + eventType, e);
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Long id = jdbc.queryForObject("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, status, created_at,
                                           next_attempt_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?)
                RETURNING id""", Long.class, aggregateType, aggregateId, eventType, json, now, now);
        return id;
    }
}
