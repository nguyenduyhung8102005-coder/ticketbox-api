package com.ticketbox.catalog.domain;

/** Trạng thái phim/sự kiện, khớp ck_shows_status. DRAFT không hiển thị cho khách. */
public enum ShowStatus {
    DRAFT,
    PUBLISHED,
    ENDED
}
