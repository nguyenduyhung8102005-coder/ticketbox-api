package com.ticketbox.booking.repository;

import com.ticketbox.booking.domain.SeatStatus;
import com.ticketbox.catalog.domain.SeatType;

/** Projection gọn của một ghế trong suất chiếu (không nạp entity, không bị Hibernate theo dõi). */
public record SeatView(Long showtimeSeatId, String rowLabel, Integer seatNumber, SeatType seatType,
                       SeatStatus status) {

    public String label() {
        return rowLabel + seatNumber;
    }
}
