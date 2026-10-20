package com.ticketbox.payment.vnpay;

import com.ticketbox.payment.service.PaymentProperties;

import java.time.Duration;

/** Cấu hình VNPay dùng chung cho unit test. */
public final class VnPayTestData {

    public static final String SECRET = "TEST_HASH_SECRET_0123456789ABCDEF";

    private VnPayTestData() {
    }

    public static PaymentProperties properties() {
        return new PaymentProperties(5, Duration.ofMinutes(1),
                new PaymentProperties.VnPay("TESTTMN1", SECRET, "2.1.0",
                        "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", "https://vnpay.test/api",
                        "http://localhost:8081/api/v1/payments/vnpay/return", "vn", "other", "127.0.0.1",
                        Duration.ofSeconds(5), Duration.ofSeconds(20)),
                new PaymentProperties.Reconciliation(true, Duration.ofMinutes(5), Duration.ofMinutes(15),
                        Duration.ofMinutes(30), Duration.ofMinutes(10), 5, 50));
    }
}
