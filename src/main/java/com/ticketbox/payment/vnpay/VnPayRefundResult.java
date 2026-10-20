package com.ticketbox.payment.vnpay;

import java.util.Map;

/** Kết quả API refund. */
public record VnPayRefundResult(String responseCode, String message, Map<String, String> raw) {

    /** 00: VNPay đã nhận yêu cầu hoàn tiền. */
    public boolean accepted() {
        return "00".equals(responseCode);
    }

    /** 94: đã có yêu cầu hoàn tiền trước đó và VNPay đang xử lý - coi như đã gửi thành công. */
    public boolean alreadyRequested() {
        return "94".equals(responseCode);
    }
}
