package com.ticketbox.payment.service;

import com.ticketbox.booking.api.BookingPaymentService;
import com.ticketbox.booking.api.ConfirmResult;
import com.ticketbox.booking.api.PayableBooking;
import com.ticketbox.common.events.PaymentRefundRequestedEvent;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.common.outbox.OutboxWriter;
import com.ticketbox.payment.domain.Payment;
import com.ticketbox.payment.domain.Payment.GatewayData;
import com.ticketbox.payment.domain.RefundReason;
import com.ticketbox.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Chốt trạng thái một giao dịch từ kết quả của cổng thanh toán. Dùng chung cho IPN và job đối soát.
 * <p>
 * Chạy trong transaction của bên gọi (đã khóa dòng payment), nên tất cả thay đổi sau là MỘT transaction:
 * <pre>
 * payments  INITIATED → SUCCESS / REFUND_PENDING / FAILED
 * bookings  → CONFIRMED (hoặc REFUND_PENDING)
 * showtime_seats HELD → SOLD
 * booking_items.ticket_code (vé có chữ ký)
 * outbox_events BOOKING_CONFIRMED / PAYMENT_REFUND_REQUESTED
 * </pre>
 */
@Slf4j
@Component
public class PaymentProcessor {

    private final BookingPaymentService bookingPayments;
    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    public PaymentProcessor(BookingPaymentService bookingPayments, PaymentRepository paymentRepository,
                            OutboxWriter outboxWriter, Clock clock) {
        this.bookingPayments = bookingPayments;
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProcessOutcome apply(Payment payment, GatewayResult result) {
        Instant now = clock.instant();
        GatewayData data = result.toGatewayData(now);

        if (!result.paid()) {
            payment.markFailed(data);
            log.info("Giao dịch {} thất bại, mã {}", payment.getTxnRef(), result.responseCode());
            return ProcessOutcome.FAILED;
        }

        // Xác nhận đơn TRƯỚC khi đổi trạng thái payment: nếu đơn đã được giao dịch khác thanh toán thì giao dịch
        // này không được thành SUCCESS (unique index uk_payments_booking_success)
        ConfirmResult confirm = bookingPayments.confirmPaid(payment.getBookingId(), data.paidAt());
        if (confirm.isConfirmed()) {
            payment.markSucceeded(data);
            paymentRepository.flush();
            return confirm == ConfirmResult.CONFIRMED_LATE ? ProcessOutcome.CONFIRMED_LATE : ProcessOutcome.CONFIRMED;
        }

        RefundReason reason = switch (confirm) {
            case ALREADY_CONFIRMED -> RefundReason.DUPLICATE_PAYMENT;
            case SEATS_UNAVAILABLE -> RefundReason.SEATS_UNAVAILABLE;
            default -> RefundReason.BOOKING_CLOSED;
        };
        payment.markRefundRequired(data, reason);
        paymentRepository.flush();

        PayableBooking booking = bookingPayments.findById(payment.getBookingId()).orElseThrow();
        outboxWriter.append(EventTypes.AGGREGATE_PAYMENT, payment.getId(), EventTypes.PAYMENT_REFUND_REQUESTED,
                new PaymentRefundRequestedEvent(payment.getTxnRef(), booking.bookingCode(),
                        booking.customerEmail(), payment.getAmount(), reason.name()));
        log.warn("Giao dịch {} đã thu tiền nhưng không giao được vé đơn {} ({}): chờ hoàn tiền",
                payment.getTxnRef(), booking.bookingCode(), reason);
        return ProcessOutcome.REFUND_REQUIRED;
    }
}
