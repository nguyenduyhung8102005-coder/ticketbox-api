package com.ticketbox.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Payload của BOOKING_CONFIRMED. Mang đủ thông tin để gửi email vé (event-carried state transfer):
 * module notification không phải đọc bảng của module booking.
 */
public record BookingConfirmedEvent(
        String bookingCode,
        String customerEmail,
        String showTitle,
        Instant startTime,
        String venueName,
        String venueAddress,
        String auditoriumName,
        List<Ticket> tickets,
        BigDecimal totalAmount,
        Instant confirmedAt) {

    /** @param ticketCode mã vé đã ký, chính là nội dung QR */
    public record Ticket(String seatLabel, String seatType, BigDecimal price, String ticketCode) {
    }
}
