package com.ticketbox.payment.domain;

/**
 * Vòng đời giao dịch (khớp ck_payments_status):
 * <pre>
 * INITIATED ──IPN/đối soát: thành công──▶ SUCCESS
 *     │                    └─ nhưng không giao được vé ─▶ REFUND_PENDING ──▶ REFUNDED
 *     └──IPN/đối soát: thất bại / bỏ dở──▶ FAILED
 * </pre>
 */
public enum PaymentStatus {
    INITIATED,
    SUCCESS,
    FAILED,
    REFUND_PENDING,
    REFUNDED
}
