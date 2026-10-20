package com.ticketbox.common.events;

import java.math.BigDecimal;

/**
 * Payload của PAYMENT_REFUND_REQUESTED: đã thu tiền nhưng không giao được vé.
 *
 * @param txnRef mã giao dịch phía merchant (vnp_TxnRef), dùng để tìm lại payment (không dùng id số
 *               vì id có thể bị tái sử dụng khi dọn dữ liệu test)
 * @param reason SEATS_UNAVAILABLE | DUPLICATE_PAYMENT | BOOKING_CLOSED
 */
public record PaymentRefundRequestedEvent(
        String txnRef,
        String bookingCode,
        String customerEmail,
        BigDecimal amount,
        String reason) {
}
