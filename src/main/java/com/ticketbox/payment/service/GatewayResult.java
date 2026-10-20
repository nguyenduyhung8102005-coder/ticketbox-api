package com.ticketbox.payment.service;

import com.ticketbox.payment.domain.Payment.GatewayData;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Kết quả một giao dịch theo cổng thanh toán, dựng từ IPN hoặc từ API truy vấn (đối soát).
 * Hai nguồn đi chung một luồng xử lý ({@link PaymentProcessor}) nên kết quả luôn nhất quán.
 *
 * @param amount số tiền VND (đã chia 100 so với vnp_Amount)
 * @param paid   cổng thanh toán xác nhận đã trừ tiền thành công
 */
public record GatewayResult(
        String txnRef,
        BigDecimal amount,
        boolean paid,
        String responseCode,
        String transactionStatus,
        String transactionNo,
        String bankCode,
        Instant payDate,
        Map<String, String> raw) {

    public GatewayData toGatewayData(Instant fallbackPaidAt) {
        return new GatewayData(responseCode, transactionNo, bankCode,
                paid ? (payDate != null ? payDate : fallbackPaidAt) : null, raw);
    }
}
