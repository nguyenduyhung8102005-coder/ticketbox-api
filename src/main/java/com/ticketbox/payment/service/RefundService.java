package com.ticketbox.payment.service;

import com.ticketbox.booking.api.BookingPaymentService;
import com.ticketbox.booking.api.PayableBooking;
import com.ticketbox.common.events.PaymentRefundedEvent;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.common.outbox.OutboxWriter;
import com.ticketbox.payment.domain.Payment;
import com.ticketbox.payment.domain.PaymentProvider;
import com.ticketbox.payment.domain.PaymentStatus;
import com.ticketbox.payment.repository.PaymentRepository;
import com.ticketbox.payment.vnpay.VnPayApiClient;
import com.ticketbox.payment.vnpay.VnPayApiException;
import com.ticketbox.payment.vnpay.VnPayRefundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * Hoàn tiền cho giao dịch REFUND_PENDING.
 * <p>
 * Không giữ transaction (và khóa dòng) trong lúc gọi HTTP sang VNPay: (1) transaction ngắn kiểm tra trạng thái
 * và tăng refund_attempts, (2) gọi API, (3) transaction ngắn chốt REFUNDED + outbox PAYMENT_REFUNDED.
 * Gọi lại nhiều lần an toàn: giao dịch không còn REFUND_PENDING thì bỏ qua.
 */
@Slf4j
@Service
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final BookingPaymentService bookingPayments;
    private final VnPayApiClient vnPayApiClient;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RefundService(PaymentRepository paymentRepository, BookingPaymentService bookingPayments,
                         VnPayApiClient vnPayApiClient, OutboxWriter outboxWriter, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.bookingPayments = bookingPayments;
        this.vnPayApiClient = vnPayApiClient;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * @return true nếu đã hoàn tiền trong lần gọi này; false nếu không còn gì để làm
     * @throws VnPayApiException VNPay lỗi hoặc từ chối: bên gọi thử lại sau (consumer Kafka retry, job đối soát)
     */
    public boolean refund(String txnRef) {
        RefundTarget target = tx.execute(status -> paymentRepository
                .findByTxnRefForUpdate(PaymentProvider.VNPAY, txnRef)
                .filter(p -> p.getStatus() == PaymentStatus.REFUND_PENDING)
                .map(p -> {
                    p.recordRefundAttempt();
                    return new RefundTarget(p.getTxnRef(), p.getAmount(), p.getTransactionNo(), p.getCreatedAt(),
                            p.getRefundAttempts());
                })
                .orElse(null));
        if (target == null) {
            return false;
        }

        VnPayRefundResult result = vnPayApiClient.refund(target.txnRef(), target.amount(), target.transactionNo(),
                target.createdAt());
        if (result == null || !(result.accepted() || result.alreadyRequested())) {
            String code = result == null ? "null" : result.responseCode() + " " + result.message();
            throw new VnPayApiException("VNPay từ chối hoàn tiền " + txnRef + " (lần " + target.attempt() + "): "
                    + code);
        }
        return completeRefund(txnRef);
    }

    /**
     * Chốt đã hoàn tiền (VNPay đã nhận yêu cầu, hoặc truy vấn thấy giao dịch đang/đã hoàn).
     *
     * @return false nếu giao dịch không còn REFUND_PENDING
     */
    public boolean completeRefund(String txnRef) {
        Boolean done = tx.execute(status -> {
            Payment payment = paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, txnRef)
                    .filter(p -> p.getStatus() == PaymentStatus.REFUND_PENDING)
                    .orElse(null);
            if (payment == null) {
                return false;
            }
            Instant now = clock.instant();
            payment.markRefunded(now);
            bookingPayments.markRefunded(payment.getBookingId());   // chỉ đổi đơn đang REFUND_PENDING
            PayableBooking booking = bookingPayments.findById(payment.getBookingId()).orElseThrow();
            outboxWriter.append(EventTypes.AGGREGATE_PAYMENT, payment.getId(), EventTypes.PAYMENT_REFUNDED,
                    new PaymentRefundedEvent(payment.getTxnRef(), booking.bookingCode(), booking.customerEmail(),
                            payment.getAmount(),
                            payment.getRefundReason() == null ? null : payment.getRefundReason().name(), now));
            log.info("Đã hoàn tiền giao dịch {} (đơn {}), {} VND", txnRef, booking.bookingCode(),
                    payment.getAmount());
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private record RefundTarget(String txnRef, BigDecimal amount, String transactionNo, Instant createdAt,
                                int attempt) {
    }
}
