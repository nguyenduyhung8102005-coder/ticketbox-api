package com.ticketbox.booking.domain;

/**
 * Vòng đời ghế trong một suất chiếu (khớp ck_showtime_seats_status):
 * <pre>
 * AVAILABLE ──hold──▶ HELD ──payment success──▶ SOLD
 *     ▲                │
 *     └──expire/cancel─┘
 * </pre>
 * BLOCKED: ghế bị khóa không bán (hỏng, giữ cho khách mời...).
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    SOLD,
    BLOCKED
}
