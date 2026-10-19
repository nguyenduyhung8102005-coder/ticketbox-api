package com.ticketbox.booking;

import com.ticketbox.AbstractIntegrationTest;
import com.ticketbox.booking.dto.BookingDtos.HoldSeatsCommand;
import com.ticketbox.booking.repository.ShowtimeSeatRepository;
import com.ticketbox.booking.service.BookingService;
import com.ticketbox.booking.service.SeatHoldCache;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Chứng minh chống bán trùng ghế khi nhiều người bấm cùng lúc, trên PostgreSQL và Redis THẬT.
 * <p>
 * Mỗi test dùng {@link CountDownLatch} làm "vạch xuất phát": mọi luồng chuẩn bị xong rồi đứng chờ,
 * latch mở thì tất cả lao vào cùng một ghế, tạo ra tranh chấp thật thay vì chạy lần lượt.
 */
@Slf4j
class BookingConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 100;

    @Autowired
    private BookingService bookingService;
    @Autowired
    private ShowtimeSeatRepository showtimeSeatRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ShowtimeFixture fixture;
    private long f7;

    @BeforeEach
    void setUpHotShowtime() {
        fixture = createOnSaleShowtime(6, 10);   // hàng A..F, mỗi hàng 10 ghế
        f7 = seatId(fixture.showtimeId(), "F", 7);
    }

    @Test
    @DisplayName("100 người cùng giữ ghế F7: đúng 1 người thành công, 99 người nhận SEAT_ALREADY_TAKEN")
    void only_one_user_can_hold_same_seat_under_concurrency() throws Exception {
        List<Long> users = createUsers(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        runConcurrently(THREADS, i -> () -> {
            try {
                bookingService.holdSeats(new HoldSeatsCommand(users.get(i), "user" + i + "@test.vn",
                        fixture.showtimeId(), List.of(f7), "key-" + i + "-abcdef"));
                success.incrementAndGet();
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.SEAT_ALREADY_TAKEN) {
                    taken.incrementAndGet();
                } else {
                    unexpected.add(e);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            }
            return null;
        });

        assertThat(unexpected).as("lỗi ngoài dự kiến").isEmpty();
        assertThat(success.get()).isEqualTo(1);
        assertThat(taken.get()).isEqualTo(THREADS - 1);
        assertThat(seatStatus(f7)).isEqualTo("HELD");
        // 99 request thất bại không để lại đơn rác: transaction của chúng không bao giờ bắt đầu hoặc đã rollback
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_items", Integer.class)).isEqualTo(1);
        String owner = jdbc.queryForObject("SELECT booking_code FROM bookings", String.class);
        assertThat(redis.opsForValue().get(SeatHoldCache.key(fixture.showtimeId(), f7))).isEqualTo(owner);
    }

    @Test
    @DisplayName("Bỏ qua Redis, chỉ còn UPDATE có điều kiện ở PostgreSQL: vẫn đúng 1 người giữ được ghế")
    void database_conditional_update_alone_prevents_double_booking() throws Exception {
        int threads = 50;
        List<Long> bookingIds = createPendingBookings(threads);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Instant heldUntil = Instant.now().plus(Duration.ofMinutes(10));
        AtomicInteger rowsUpdated = new AtomicInteger();

        runConcurrently(threads, i -> () -> {
            Integer updated = tx.execute(status -> showtimeSeatRepository.holdSeats(
                    fixture.showtimeId(), List.of(f7), bookingIds.get(i), heldUntil));
            rowsUpdated.addAndGet(updated == null ? 0 : updated);
            return null;
        });

        assertThat(rowsUpdated.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM showtime_seats WHERE status = 'HELD'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("KHÔNG có cơ chế khóa (đọc rồi mới ghi): nhiều người cùng 'mua' được một ghế")
    void without_locking_the_same_seat_is_sold_to_many_users() throws Exception {
        int threads = 20;
        List<Long> bookingIds = createPendingBookings(threads);
        Queue<Long> believesTheyOwnSeat = new ConcurrentLinkedQueue<>();
        Timestamp heldUntil = Timestamp.from(Instant.now().plus(Duration.ofMinutes(10)));

        // Cách làm SAI hay gặp: SELECT kiểm tra ghế trống, xử lý gì đó, rồi UPDATE không điều kiện
        runConcurrently(threads, i -> () -> {
            String status = jdbc.queryForObject("SELECT status FROM showtime_seats WHERE id = ?", String.class, f7);
            if ("AVAILABLE".equals(status)) {
                Thread.sleep(200);   // giả lập xử lý: tính tiền, gọi dịch vụ khác...
                jdbc.update("UPDATE showtime_seats SET status = 'HELD', booking_id = ?, held_until = ? WHERE id = ?",
                        bookingIds.get(i), heldUntil, f7);
                believesTheyOwnSeat.add(bookingIds.get(i));
            }
            return null;
        });

        log.warn("KHÔNG KHÓA: {} người cùng tin rằng mình đã giữ được ghế F7", believesTheyOwnSeat.size());
        // Tất cả đều đọc thấy AVAILABLE trước khi ai kịp ghi: bán trùng ghế
        assertThat(believesTheyOwnSeat.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("All-or-nothing: giữ [F7, F8] khi F8 đã có người giữ thì F7 cũng không bị giữ")
    void hold_is_all_or_nothing() {
        long f8 = seatId(fixture.showtimeId(), "F", 8);
        List<Long> users = createUsers(2);
        bookingService.holdSeats(new HoldSeatsCommand(users.get(0), "user0@test.vn",
                fixture.showtimeId(), List.of(f8), "first-user-key"));

        Throwable error = catchThrowable(() -> bookingService.holdSeats(new HoldSeatsCommand(users.get(1),
                "user1@test.vn", fixture.showtimeId(), List.of(f7, f8), "second-user-key")));

        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_TAKEN);
        assertThat(seatStatus(f7)).isEqualTo("AVAILABLE");
        assertThat(redis.hasKey(SeatHoldCache.key(fixture.showtimeId(), f7))).isFalse();
    }

    @Test
    @DisplayName("Redis mất key (restart/flush) giữa chừng: PostgreSQL vẫn chặn được, Redis không bị kẹt key rác")
    void database_still_protects_when_redis_loses_its_keys() {
        List<Long> users = createUsers(2);
        bookingService.holdSeats(new HoldSeatsCommand(users.get(0), "user0@test.vn",
                fixture.showtimeId(), List.of(f7), "first-user-key"));
        redis.delete(SeatHoldCache.key(fixture.showtimeId(), f7));   // giả lập Redis mất dữ liệu

        Throwable error = catchThrowable(() -> bookingService.holdSeats(new HoldSeatsCommand(users.get(1),
                "user1@test.vn", fixture.showtimeId(), List.of(f7), "second-user-key")));

        assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_TAKEN);
        // Request thứ hai đã giữ key trong Redis rồi bị DB từ chối -> phải tự nhả key của mình
        assertThat(redis.hasKey(SeatHoldCache.key(fixture.showtimeId(), f7))).isFalse();
    }

    @Test
    @DisplayName("50 người tranh các cặp ghế chồng lấn: không ghế nào thuộc hai đơn, mỗi đơn có đủ ghế của mình")
    void overlapping_multi_seat_requests_never_share_a_seat() throws Exception {
        int threads = 50;
        List<Long> users = createUsers(threads);
        List<Long> rowA = new ArrayList<>();
        for (int n = 1; n <= 10; n++) {
            rowA.add(seatId(fixture.showtimeId(), "A", n));
        }
        AtomicInteger success = new AtomicInteger();

        runConcurrently(threads, i -> () -> {
            // Người i muốn 2 ghế liền nhau A(i%9+1), A(i%9+2): nhiều người tranh cùng một ghế
            List<Long> pair = List.of(rowA.get(i % 9), rowA.get(i % 9 + 1));
            try {
                bookingService.holdSeats(new HoldSeatsCommand(users.get(i), "user" + i + "@test.vn",
                        fixture.showtimeId(), pair, "pair-key-" + i));
                success.incrementAndGet();
            } catch (BusinessException ignored) {
                // SEAT_ALREADY_TAKEN là kết quả hợp lệ
            }
            return null;
        });

        assertThat(success.get()).isBetween(1, 5);   // 10 ghế, mỗi đơn 2 ghế -> tối đa 5 đơn
        Integer heldSeats = jdbc.queryForObject(
                "SELECT count(*) FROM showtime_seats WHERE status = 'HELD'", Integer.class);
        assertThat(heldSeats).isEqualTo(success.get() * 2);
        // Mỗi ghế HELD trỏ về đúng đơn có booking_item cho ghế đó
        Integer mismatched = jdbc.queryForObject("""
                SELECT count(*) FROM showtime_seats ss
                 WHERE ss.status = 'HELD'
                   AND NOT EXISTS (SELECT 1 FROM booking_items bi
                                    WHERE bi.showtime_seat_id = ss.id AND bi.booking_id = ss.booking_id)""",
                Integer.class);
        assertThat(mismatched).isZero();
    }

    // ----- helpers -----

    @FunctionalInterface
    private interface TaskFactory {
        Callable<Void> create(int index);
    }

    /** Tạo sẵn N luồng, cho tất cả chờ ở vạch xuất phát rồi thả cùng lúc. */
    private static void runConcurrently(int threads, TaskFactory factory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Void> task = factory.create(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** Đơn PENDING giả để có booking_id hợp lệ (khóa ngoại) cho các test thao tác thẳng vào showtime_seats. */
    private List<Long> createPendingBookings(int count) {
        long userId = createUser("raw@test.vn", com.ticketbox.user.domain.Role.CUSTOMER);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(jdbc.queryForObject("""
                    INSERT INTO bookings (booking_code, user_id, customer_email, showtime_id, status,
                                          total_amount, expires_at)
                    VALUES (?, ?, 'raw@test.vn', ?, 'PENDING_PAYMENT', 90000, now() + interval '10 minutes')
                    RETURNING id""", Long.class, "RAW" + i, userId, fixture.showtimeId()));
        }
        return ids;
    }
}
