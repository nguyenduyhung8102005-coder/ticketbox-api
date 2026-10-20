package com.ticketbox.booking.dto;

import com.ticketbox.booking.domain.BookingStatus;
import com.ticketbox.booking.domain.SeatStatus;
import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BookingDtos {

    private BookingDtos() {
    }

    /**
     * @param showtimeSeatIds id lấy từ trường {@code showtimeSeatId} của API sơ đồ ghế
     *                        GET /api/v1/showtimes/{id}/seats (không phải id ghế vật lý)
     */
    public record HoldSeatsRequest(
            @NotNull @Schema(example = "1") Long showtimeId,
            @NotEmpty @Schema(example = "[101, 102]") List<@NotNull Long> showtimeSeatIds) {
    }

    /** Lệnh giữ ghế ở tầng service (đã gắn người dùng và Idempotency-Key). */
    public record HoldSeatsCommand(Long userId, String customerEmail, Long showtimeId,
                                   List<Long> showtimeSeatIds, String idempotencyKey) {
    }

    /** @param ticketCode mã vé (nội dung QR), chỉ có khi đơn đã CONFIRMED */
    public record BookedSeat(Long showtimeSeatId, String label, SeatType seatType, BigDecimal price,
                             String ticketCode) {
    }

    public record BookingResponse(
            String bookingCode,
            BookingStatus status,
            Long showtimeId,
            String showTitle,
            Instant startTime,
            String venueName,
            String auditoriumName,
            List<BookedSeat> seats,
            BigDecimal totalAmount,
            Instant expiresAt,
            Instant createdAt,
            Instant confirmedAt) {
    }

    public record SeatMapSeat(Long showtimeSeatId, String rowLabel, Integer seatNumber, String label,
                              SeatType seatType, SeatStatus status, BigDecimal price) {
    }

    public record SeatMapResponse(
            Long showtimeId,
            String showTitle,
            Instant startTime,
            ShowtimeStatus showtimeStatus,
            String venueName,
            String auditoriumName,
            Map<SeatType, BigDecimal> prices,
            int availableCount,
            List<SeatMapSeat> seats) {
    }
}
