package com.ticketbox.payment.domain;

/** Loại bản ghi trong payment_logs (khớp ck_payment_logs_kind). */
public enum PaymentLogKind {
    IPN,
    RETURN,
    QUERY,
    REFUND
}
