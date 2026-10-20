package com.ticketbox.booking.repository;

import com.ticketbox.booking.domain.BookingStatus;

/** Một vé đã phát hành kèm trạng thái đơn hiện tại. */
public record TicketView(Long bookingItemId, String ticketCode, String bookingCode, BookingStatus bookingStatus,
                         Long showtimeId, Long showtimeSeatId, String rowLabel, Integer seatNumber) {

    public String seatLabel() {
        return rowLabel + seatNumber;
    }
}
