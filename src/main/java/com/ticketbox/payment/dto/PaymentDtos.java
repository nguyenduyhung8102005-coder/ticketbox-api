package com.ticketbox.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * @param bankCode để trống: khách tự chọn trên trang VNPay. VNPAYQR (QR), VNBANK (thẻ ATM/tài khoản nội địa),
     *                 INTCARD (thẻ quốc tế), hoặc mã ngân hàng cụ thể như NCB (thẻ test sandbox)
     */
    public record CreatePaymentRequest(
            @NotBlank @Size(max = 20) @Schema(example = "TB7KQ2M9XA4F") String bookingCode,
            @Pattern(regexp = "^[A-Z0-9]{2,20}$", message = "bankCode gồm 2–20 ký tự chữ in hoa hoặc số")
            @Schema(example = "NCB") String bankCode) {
    }

    /** Lệnh tạo thanh toán ở tầng service (đã gắn người dùng và IP). */
    public record CreatePaymentCommand(Long userId, boolean admin, String bookingCode, String bankCode,
                                       String clientIp) {
    }

    /** @param paymentUrl client redirect người dùng tới địa chỉ này */
    public record PaymentUrlResponse(String txnRef, String paymentUrl, BigDecimal amount, Instant expiresAt) {
    }

    /**
     * Kết quả hiển thị trên trang Return. Chỉ để HIỂN THỊ: trạng thái thật do IPN quyết định.
     *
     * @param gatewaySuccess  VNPay báo thành công trên trình duyệt
     * @param bookingStatus   trạng thái đơn trong hệ thống tại thời điểm xem
     */
    public record PaymentReturnResponse(
            String txnRef,
            String bookingCode,
            String paymentStatus,
            String bookingStatus,
            String gatewayResponseCode,
            boolean gatewaySuccess,
            String message) {
    }

    public record ReconciliationReport(int checked, int confirmed, int failed, int refundRequired,
                                       int refunded, int stillPending, int errors) {
    }
}
