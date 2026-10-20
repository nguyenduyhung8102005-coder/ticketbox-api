package com.ticketbox.booking.api;

import com.ticketbox.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Thông tin đơn mà module payment cần (bản chụp, không phải entity: module khác không sửa trực tiếp đơn). */
public record PayableBooking(
        Long id,
        String bookingCode,
        Long userId,
        String customerEmail,
        BookingStatus status,
        BigDecimal totalAmount,
        Instant expiresAt) {

    public boolean isOwnedBy(Long userId) {
        return this.userId != null && this.userId.equals(userId);
    }
}
