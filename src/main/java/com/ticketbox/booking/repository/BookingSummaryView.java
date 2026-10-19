package com.ticketbox.booking.repository;

import com.ticketbox.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Một dòng trong lịch sử đặt vé. Dựng trực tiếp từ JPQL, 1 câu SQL cho cả trang. */
public record BookingSummaryView(String bookingCode, BookingStatus status, BigDecimal totalAmount,
                                 Instant expiresAt, Instant createdAt, Long showtimeId, String showTitle,
                                 Instant startTime) {
}
