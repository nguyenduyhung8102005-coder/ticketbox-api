package com.ticketbox.payment.vnpay;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param createdAt thời điểm tạo giao dịch (vnp_CreateDate); phải giữ nguyên để sau này gửi lại làm
 *                  vnp_TransactionDate khi truy vấn/hoàn tiền
 * @param expireAt  VNPay từ chối thanh toán sau thời điểm này (vnp_ExpireDate) = hạn giữ ghế
 * @param bankCode  null: khách tự chọn phương thức trên trang VNPay
 */
public record PaymentUrlRequest(String txnRef, BigDecimal amount, String orderInfo, String clientIp,
                                Instant createdAt, Instant expireAt, String bankCode) {
}
