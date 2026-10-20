package com.ticketbox.payment.service;

public enum ProcessOutcome {
    /** Thanh toán thành công, đơn đã xác nhận. */
    CONFIRMED,
    /** Thanh toán thành công sau khi giữ chỗ hết hạn, ghế vẫn còn trống nên vẫn xác nhận. */
    CONFIRMED_LATE,
    /** Thanh toán thành công nhưng không giao được vé: chờ hoàn tiền. */
    REFUND_REQUIRED,
    /** Thanh toán thất bại / bị hủy. */
    FAILED
}
