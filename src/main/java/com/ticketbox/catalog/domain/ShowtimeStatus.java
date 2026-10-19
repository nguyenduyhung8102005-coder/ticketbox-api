package com.ticketbox.catalog.domain;

/** Trạng thái suất chiếu, khớp ck_showtimes_status. Chỉ ON_SALE mới đặt vé được. */
public enum ShowtimeStatus {
    SCHEDULED,
    ON_SALE,
    CANCELLED,
    FINISHED
}
