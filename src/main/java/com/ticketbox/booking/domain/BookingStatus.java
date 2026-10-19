package com.ticketbox.booking.domain;

/**
 * Vòng đời đơn (khớp ck_bookings_status):
 * <pre>
 * PENDING_PAYMENT ──▶ CONFIRMED ──▶ (REFUND_PENDING ──▶ REFUNDED)
 *       │
 *       └──▶ EXPIRED / CANCELLED
 * </pre>
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED
}
