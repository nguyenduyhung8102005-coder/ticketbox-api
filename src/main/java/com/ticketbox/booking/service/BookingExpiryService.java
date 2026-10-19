package com.ticketbox.booking.service;

import com.ticketbox.booking.domain.Booking;
import com.ticketbox.booking.repository.BookingRepository;
import com.ticketbox.booking.repository.ShowtimeSeatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Trả ghế của các đơn quá hạn giữ chỗ mà chưa thanh toán: đơn -> EXPIRED, ghế -> AVAILABLE.
 * <p>
 * Chạy an toàn khi có nhiều instance ứng dụng: mỗi lô đơn được khóa bằng
 * {@code FOR UPDATE SKIP LOCKED}, instance khác gặp dòng đang khóa sẽ bỏ qua và lấy đơn khác.
 */
@Slf4j
@Service
public class BookingExpiryService {

    /** Giới hạn số lô mỗi lần chạy để một lượt job không kéo dài vô hạn khi tồn đọng lớn. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final BookingRepository bookingRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final SeatHoldCache seatHoldCache;
    private final BookingProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BookingExpiryService(BookingRepository bookingRepository,
                                ShowtimeSeatRepository showtimeSeatRepository,
                                SeatHoldCache seatHoldCache,
                                BookingProperties properties,
                                Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.showtimeSeatRepository = showtimeSeatRepository;
        this.seatHoldCache = seatHoldCache;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** @return số đơn đã chuyển sang EXPIRED */
    public int expireDueBookings() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<ReleaseTarget> released = tx.execute(status -> expireOneBatch());
            if (released == null || released.isEmpty()) {
                break;
            }
            // Sau commit mới nhả Redis (key thường đã tự hết hạn trước đó, đây là dọn dẹp cho chắc)
            released.forEach(t -> seatHoldCache.releaseAll(t.showtimeId(), t.seatIds(), t.bookingCode()));
            total += released.size();
            if (released.size() < properties.expiryJob().batchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("Đã hết hạn {} đơn giữ chỗ, ghế được trả về AVAILABLE", total);
        }
        return total;
    }

    private List<ReleaseTarget> expireOneBatch() {
        Instant now = clock.instant();
        List<Long> ids = bookingRepository.lockExpiredPendingIds(now, properties.expiryJob().batchSize());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Booking> bookings = bookingRepository.findAllWithItemsByIdIn(ids);
        showtimeSeatRepository.releaseHeldSeatsOfBookings(ids);
        bookings.forEach(Booking::expire);
        return bookings.stream()
                .map(b -> new ReleaseTarget(b.getShowtimeId(), b.showtimeSeatIds(), b.getBookingCode()))
                .toList();
    }

    private record ReleaseTarget(Long showtimeId, List<Long> seatIds, String bookingCode) {
    }
}
