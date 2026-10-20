package com.ticketbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test cho hạ tầng: ứng dụng khởi động được với PostgreSQL, Redis, Kafka thật,
 * Flyway chạy đủ migration, và các ràng buộc quan trọng ở tầng DB hoạt động đúng.
 * Kế thừa AbstractIntegrationTest để dùng chung context (và container) với các IT khác.
 * Cần Docker đang chạy (Testcontainers).
 */
class InfrastructureIT extends AbstractIntegrationTest {

    private long auditoriumId;
    private long showId;

    @BeforeEach
    void seedCatalog() {
        jdbc.execute("TRUNCATE shows, venues RESTART IDENTITY CASCADE");
        long venueId = insertReturningId(
                "INSERT INTO venues (name, city, address) VALUES ('Rạp Test', 'Hà Nội', '1 Tràng Tiền') RETURNING id");
        auditoriumId = insertReturningId(
                "INSERT INTO auditoriums (venue_id, name) VALUES (?, 'Phòng 1') RETURNING id", venueId);
        showId = insertReturningId("""
                INSERT INTO shows (type, title, slug, duration_min, status)
                VALUES ('MOVIE', 'Phim Test', 'phim-test', 120, 'PUBLISHED') RETURNING id""");
    }

    @Test
    void flyway_appliesAllMigrations() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(5);
    }

    @Test
    void healthEndpoint_isUpWithDbAndRedis() {
        ResponseEntity<Map> response = http.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void swaggerDocs_isAvailable() {
        ResponseEntity<String> response = http.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Ticketbox API");
    }

    @Test
    void redis_isReachable() {
        redis.opsForValue().set("it:ping", "pong", Duration.ofSeconds(10));
        assertThat(redis.opsForValue().get("it:ping")).isEqualTo("pong");
    }

    @Test
    void showtime_overlappingInSameAuditorium_isRejectedByDatabase() {
        insertShowtime("2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z", "SCHEDULED");

        assertThatThrownBy(() ->
                insertShowtime("2026-11-01T11:30:00Z", "2026-11-01T13:30:00Z", "SCHEDULED"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ex_showtimes_no_overlap");
    }

    @Test
    void showtime_backToBack_isAllowed() {
        insertShowtime("2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z", "SCHEDULED");
        // Khoảng [start, end) nên suất bắt đầu đúng lúc suất trước kết thúc là hợp lệ
        insertShowtime("2026-11-01T12:00:00Z", "2026-11-01T14:00:00Z", "SCHEDULED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM showtimes", Integer.class)).isEqualTo(2);
    }

    @Test
    void showtime_cancelledDoesNotBlockNewOne() {
        insertShowtime("2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z", "CANCELLED");
        insertShowtime("2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z", "SCHEDULED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM showtimes", Integer.class)).isEqualTo(2);
    }

    @Test
    void showtimeSeat_duplicatePair_isRejectedByDatabase() {
        long showtimeId = insertShowtime("2026-11-02T10:00:00Z", "2026-11-02T12:00:00Z", "ON_SALE");
        long seatId = insertReturningId(
                "INSERT INTO seats (auditorium_id, row_label, seat_number) VALUES (?, 'F', 7) RETURNING id",
                auditoriumId);

        jdbc.update("INSERT INTO showtime_seats (showtime_id, seat_id) VALUES (?, ?)", showtimeId, seatId);

        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO showtime_seats (showtime_id, seat_id) VALUES (?, ?)", showtimeId, seatId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void showtimeSeat_inconsistentState_isRejectedByDatabase() {
        long showtimeId = insertShowtime("2026-11-03T10:00:00Z", "2026-11-03T12:00:00Z", "ON_SALE");
        long seatId = insertReturningId(
                "INSERT INTO seats (auditorium_id, row_label, seat_number) VALUES (?, 'A', 1) RETURNING id",
                auditoriumId);

        // Ghế HELD mà không gắn đơn và không có hạn giữ chỗ là dữ liệu sai -> DB phải chặn
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO showtime_seats (showtime_id, seat_id, status) VALUES (?, ?, 'HELD')",
                showtimeId, seatId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_showtime_seats_state");
    }

    @Test
    void payments_atMostOneSuccessfulPaymentPerBooking() {
        long showtimeId = insertShowtime("2026-11-04T10:00:00Z", "2026-11-04T12:00:00Z", "ON_SALE");
        long bookingId = insertReturningId("""
                INSERT INTO bookings (booking_code, customer_email, showtime_id, status, total_amount, expires_at)
                VALUES ('TBINFRA00001', 'a@test.vn', ?, 'CONFIRMED', 90000, now()) RETURNING id""", showtimeId);
        jdbc.update("""
                INSERT INTO payments (booking_id, provider, provider_txn_id, amount, status)
                VALUES (?, 'VNPAY', 'TXN1', 90000, 'SUCCESS')""", bookingId);
        // Giao dịch thứ hai cho cùng đơn có thể tồn tại (thử lại, trả 2 lần) nhưng KHÔNG được cùng là SUCCESS
        jdbc.update("""
                INSERT INTO payments (booking_id, provider, provider_txn_id, amount, status, refund_reason)
                VALUES (?, 'VNPAY', 'TXN2', 90000, 'REFUND_PENDING', 'DUPLICATE_PAYMENT')""", bookingId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payments (booking_id, provider, provider_txn_id, amount, status)
                VALUES (?, 'VNPAY', 'TXN3', 90000, 'SUCCESS')""", bookingId))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_payments_booking_success");
    }

    @Test
    void users_emailIsCaseInsensitiveUnique() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO users (email, password_hash, full_name) VALUES ('An@Gmail.com', 'x', 'An')");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (email, password_hash, full_name) VALUES ('an@gmail.com', 'x', 'An 2')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ----- helpers -----

    private long insertShowtime(String start, String end, String status) {
        return insertReturningId("""
                INSERT INTO showtimes (show_id, auditorium_id, start_time, end_time, status)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz, ?) RETURNING id""",
                showId, auditoriumId, start, end, status);
    }

    private long insertReturningId(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        assertThat(id).isNotNull();
        return id;
    }
}
