package com.ticketbox.booking.repository;

import com.ticketbox.catalog.domain.SeatType;

import java.math.BigDecimal;

/** Ghế trong một đơn kèm giá đã chụp lúc đặt và mã vé (null khi đơn chưa thanh toán). */
public record BookedSeatView(Long bookingItemId, Long showtimeSeatId, String rowLabel, Integer seatNumber,
                             SeatType seatType, BigDecimal price, String ticketCode) {

    public String label() {
        return rowLabel + seatNumber;
    }
}
