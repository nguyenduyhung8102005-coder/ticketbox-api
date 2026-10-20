package com.ticketbox.payment.domain;

/** Vì sao một giao dịch đã thu tiền lại phải hoàn (khớp ck_payments_refund_reason). */
public enum RefundReason {
    /** Khách trả tiền khi giữ chỗ đã hết hạn, và ghế đã thuộc về người khác. */
    SEATS_UNAVAILABLE,
    /** Đơn đã được thanh toán bởi một giao dịch khác (khách trả 2 lần ở 2 tab). */
    DUPLICATE_PAYMENT,
    /** Đơn đã ở trạng thái hoàn tiền từ trước. */
    BOOKING_CLOSED
}
