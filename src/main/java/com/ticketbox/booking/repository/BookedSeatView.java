package com.ticketbox.booking.repository;

import com.ticketbox.catalog.domain.SeatType;

import java.math.BigDecimal;

/** Ghế trong một đơn kèm giá đã chụp lúc đặt. */
public record BookedSeatView(Long showtimeSeatId, String rowLabel, Integer seatNumber, SeatType seatType,
                             BigDecimal price) {

    public String label() {
        return rowLabel + seatNumber;
    }
}
