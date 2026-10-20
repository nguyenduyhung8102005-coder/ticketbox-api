package com.ticketbox.payment.domain;

import com.ticketbox.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Một lần thanh toán của một đơn. Một đơn có thể có nhiều lần (thử lại sau khi thất bại), nhưng tối đa một
 * lần SUCCESS (unique index uk_payments_booking_success).
 */
@Getter
@Entity
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private PaymentProvider provider;

    /** vnp_TxnRef: do hệ thống sinh, gửi sang VNPay và nhận lại trong IPN. */
    @Column(name = "provider_txn_id", nullable = false)
    private String txnRef;

    /** vnp_TransactionNo: mã giao dịch phía VNPay, cần cho API hoàn tiền. */
    @Column(name = "provider_transaction_no")
    private String transactionNo;

    @Column(name = "bank_code")
    private String bankCode;

    /** vnp_ResponseCode cuối cùng nhận được (00 = thành công, 24 = khách hủy...). */
    @Column(name = "response_code")
    private String responseCode;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    /** Nguyên văn tham số từ cổng thanh toán đã dùng để chốt trạng thái (đối soát, tranh chấp). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response")
    private Map<String, String> rawResponse;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_reason")
    private RefundReason refundReason;

    @Column(name = "refund_attempts", nullable = false)
    private int refundAttempts;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    public static Payment initiate(Long bookingId, PaymentProvider provider, String txnRef, BigDecimal amount) {
        Payment payment = new Payment();
        payment.bookingId = bookingId;
        payment.provider = provider;
        payment.txnRef = txnRef;
        payment.amount = amount;
        payment.status = PaymentStatus.INITIATED;
        return payment;
    }

    public boolean isInitiated() {
        return status == PaymentStatus.INITIATED;
    }

    /** Thu tiền thành công và đơn đã được xác nhận. */
    public void markSucceeded(GatewayData data) {
        requireInitiated();
        applyGatewayData(data);
        this.status = PaymentStatus.SUCCESS;
    }

    /** Thu tiền thành công nhưng không giao được vé: phải hoàn tiền. */
    public void markRefundRequired(GatewayData data, RefundReason reason) {
        requireInitiated();
        applyGatewayData(data);
        this.status = PaymentStatus.REFUND_PENDING;
        this.refundReason = reason;
    }

    public void markFailed(GatewayData data) {
        requireInitiated();
        applyGatewayData(data);
        this.status = PaymentStatus.FAILED;
    }

    public void recordRefundAttempt() {
        this.refundAttempts++;
    }

    public void markRefunded(Instant at) {
        if (status != PaymentStatus.REFUND_PENDING) {
            throw new IllegalStateException("Giao dịch " + txnRef + " không ở trạng thái REFUND_PENDING");
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = at;
    }

    private void applyGatewayData(GatewayData data) {
        this.transactionNo = data.transactionNo();
        this.bankCode = data.bankCode();
        this.responseCode = data.responseCode();
        this.paidAt = data.paidAt();
        this.rawResponse = data.raw();
    }

    private void requireInitiated() {
        if (status != PaymentStatus.INITIATED) {
            throw new IllegalStateException("Giao dịch " + txnRef + " đã được chốt trạng thái " + status);
        }
    }

    /** Dữ liệu cổng thanh toán trả về, ghi vào giao dịch khi chốt trạng thái. */
    public record GatewayData(String responseCode, String transactionNo, String bankCode, Instant paidAt,
                              Map<String, String> raw) {
    }
}
