package com.ticketbox.booking.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chạy {@link BookingExpiryService} định kỳ (mặc định 30 giây). Tắt bằng app.booking.expiry-job.enabled=false
 * (profile test tắt để test tự gọi service, chủ động thời điểm chạy).
 * <p>
 * Vẫn cần job quét DB ngay cả khi sau này nghe sự kiện hết hạn key của Redis,
 * vì sự kiện Redis là fire-and-forget, có thể mất.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.booking.expiry-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BookingExpiryScheduler {

    private final BookingExpiryService expiryService;

    public BookingExpiryScheduler(BookingExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString = "${app.booking.expiry-job.interval:PT30S}",
            initialDelayString = "${app.booking.expiry-job.interval:PT30S}")
    public void run() {
        try {
            expiryService.expireDueBookings();
        } catch (RuntimeException e) {
            // Không để exception làm dừng lịch chạy; lượt sau thử lại
            log.error("Job trả ghế hết hạn bị lỗi", e);
        }
    }
}
