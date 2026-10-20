package com.ticketbox.booking.ticket;

/** Nội dung được ký bên trong mã vé. */
public record TicketClaims(long bookingItemId, long showtimeId, long showtimeSeatId) {
}
