package com.ticketbox.payment.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cấu hình thanh toán (prefix app.payment).
 *
 * @param maxAttemptsPerBooking số lần tối đa tạo link thanh toán cho một đơn
 * @param minRemainingHold      không tạo link mới nếu giữ chỗ còn ít hơn khoảng này (khách không kịp trả tiền)
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(
        @DefaultValue("5") int maxAttemptsPerBooking,
        @DefaultValue("PT1M") Duration minRemainingHold,
        @DefaultValue VnPay vnpay,
        @DefaultValue Reconciliation reconciliation) {

    /**
     * Thông số VNPay. Đăng ký tài khoản sandbox tại https://sandbox.vnpayment.vn/devreg/ để nhận
     * vnp_TmnCode và vnp_HashSecret qua email.
     *
     * @param returnUrl trang khách được chuyển về sau khi thanh toán (chỉ để HIỂN THỊ kết quả)
     * @param serverIp  IP của máy chủ, gửi kèm khi gọi API truy vấn/hoàn tiền
     */
    public record VnPay(
            String tmnCode,
            String hashSecret,
            @DefaultValue("2.1.0") String version,
            @DefaultValue("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html") String payUrl,
            @DefaultValue("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction") String apiUrl,
            @DefaultValue("http://localhost:8081/api/v1/payments/vnpay/return") String returnUrl,
            @DefaultValue("vn") String locale,
            @DefaultValue("other") String orderType,
            @DefaultValue("127.0.0.1") String serverIp,
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT20S") Duration readTimeout) {
    }

    /**
     * Job đối soát: lưới an toàn khi IPN bị mất hoặc hoàn tiền chưa xong.
     *
     * @param initiatedAfter    giao dịch INITIATED quá lâu hơn khoảng này thì hỏi VNPay
     * @param abandonAfter      VNPay báo chưa hoàn tất / không có giao dịch, và đã quá khoảng này: coi như khách bỏ dở
     * @param refundRetryAfter  REFUND_PENDING không thay đổi quá khoảng này thì thử hoàn tiền lại
     * @param maxRefundAttempts quá số lần thì dừng tự động, chờ xử lý tay
     */
    public record Reconciliation(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT5M") Duration interval,
            @DefaultValue("PT15M") Duration initiatedAfter,
            @DefaultValue("PT30M") Duration abandonAfter,
            @DefaultValue("PT10M") Duration refundRetryAfter,
            @DefaultValue("5") int maxRefundAttempts,
            @DefaultValue("50") int batchSize) {
    }
}
