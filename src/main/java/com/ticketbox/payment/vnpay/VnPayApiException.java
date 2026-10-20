package com.ticketbox.payment.vnpay;

/** Gọi API VNPay thất bại (mạng, HTTP lỗi, phản hồi sai chữ ký...). Nên thử lại sau. */
public class VnPayApiException extends RuntimeException {

    public VnPayApiException(String message) {
        super(message);
    }

    public VnPayApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
