package com.ticketbox.booking.api;

/** Kết quả khi module payment báo "đơn này đã được thanh toán". */
public enum ConfirmResult {

    /** Bình thường: đơn đang giữ ghế, nay đã bán và phát hành vé. */
    CONFIRMED,

    /** Ca khó: đơn đã hết hạn/bị hủy nhưng ghế vẫn còn trống → giành lại ghế và xác nhận như bình thường. */
    CONFIRMED_LATE,

    /** Đơn đã được xác nhận bởi MỘT GIAO DỊCH KHÁC (khách trả tiền 2 lần) → giao dịch này phải hoàn tiền. */
    ALREADY_CONFIRMED,

    /** Ca khó: đơn đã hết hạn và có ghế đã thuộc về người khác → đơn chuyển REFUND_PENDING, phải hoàn tiền. */
    SEATS_UNAVAILABLE,

    /** Đơn đang/đã hoàn tiền từ trước → giao dịch này cũng phải hoàn tiền. */
    BOOKING_CLOSED;

    public boolean isConfirmed() {
        return this == CONFIRMED || this == CONFIRMED_LATE;
    }
}
