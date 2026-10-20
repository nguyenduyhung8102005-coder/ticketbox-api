package com.ticketbox.common.events;

import java.math.BigDecimal;
import java.time.Instant;

/** Payload của PAYMENT_REFUNDED: cổng thanh toán đã nhận yêu cầu hoàn tiền. */
public record PaymentRefundedEvent(
        String txnRef,
        String bookingCode,
        String customerEmail,
        BigDecimal amount,
        String reason,
        Instant refundedAt) {
}
