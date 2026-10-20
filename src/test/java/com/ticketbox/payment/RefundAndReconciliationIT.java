package com.ticketbox.payment;

import com.ticketbox.AbstractIntegrationTest;
import com.ticketbox.booking.service.BookingExpiryService;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.payment.dto.PaymentDtos.ReconciliationReport;
import com.ticketbox.payment.service.PaymentReconciliationService;
import com.ticketbox.payment.vnpay.VnPayApiException;
import com.ticketbox.payment.vnpay.VnPayQueryResult;
import com.ticketbox.payment.vnpay.VnPayRefundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hoàn tiền (qua Kafka) và job đối soát. API VNPay được mock ({@code vnPayApiClient}),
 * mọi thứ khác là thật: PostgreSQL, Kafka, outbox, consumer.
 */
class RefundAndReconciliationIT extends AbstractIntegrationTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
    private static final long PRICE = 90000;

    @Autowired
    private BookingExpiryService expiryService;
    @Autowired
    private PaymentReconciliationService reconciliationService;

    private ShowtimeFixture fixture;
    private long a1;
    private String alice;

    @BeforeEach
    void setUp() {
        fixture = createOnSaleShowtime(1, 5);
        a1 = seatId(fixture.showtimeId(), "A", 1);
        alice = customerToken("alice@test.vn");
    }

    // ===================== Hoàn tiền =====================

    @Test
    void paidAfterSeatWasSold_refundRunsAutomatically_andCustomerIsInformedTwice() {
        Scenario s = paidButSeatTakenByBob();
        when(vnPayApiClient.refund(eq(s.txn()), any(), any(), any()))
                .thenReturn(new VnPayRefundResult("00", "Refund success", Map.of()));

        assertThat(outboxRelay.relayPending()).isEqualTo(1);           // PAYMENT_REFUND_REQUESTED

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> assertThat(paymentStatus(s.txn())).isEqualTo("REFUNDED"));
        assertThat(bookingStatus(s.code())).isEqualTo("REFUNDED");
        verify(vnPayApiClient).refund(eq(s.txn()), argThat(a -> a.compareTo(BigDecimal.valueOf(PRICE)) == 0),
                eq("14422574"), eq(createdAt(s.txn())));

        assertThat(outboxRelay.relayPending()).isEqualTo(1);           // PAYMENT_REFUNDED
        // Email xin lỗi (đang hoàn tiền) + email xác nhận đã hoàn tiền
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                assertThat(mailSender.withSubjectContaining(s.code())).hasSize(2));
    }

    @Test
    void refundRejected_consumerGivesUp_thenReconciliationFinishesIt() {
        Scenario s = paidButSeatTakenByBob();
        when(vnPayApiClient.refund(eq(s.txn()), any(), any(), any()))
                .thenReturn(new VnPayRefundResult("99", "System error", Map.of()));

        outboxRelay.relayPending();

        // 1 lần đầu + 2 lần retry (profile test) rồi vào DLT
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> assertThat(refundAttempts(s.txn())).isEqualTo(3));
        assertThat(paymentStatus(s.txn())).isEqualTo("REFUND_PENDING");

        // 10 phút sau, job đối soát hỏi VNPay: giao dịch vẫn chỉ là "đã thanh toán" → gọi hoàn tiền lại
        jdbc.update("UPDATE payments SET updated_at = now() - interval '11 minutes' WHERE provider_txn_id = ?",
                s.txn());
        when(vnPayApiClient.queryTransaction(eq(s.txn()), any())).thenReturn(query(s.txn(), "00", "00"));
        when(vnPayApiClient.refund(eq(s.txn()), any(), any(), any()))
                .thenReturn(new VnPayRefundResult("00", "Refund success", Map.of()));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.refunded()).isEqualTo(1);
        assertThat(paymentStatus(s.txn())).isEqualTo("REFUNDED");
        assertThat(bookingStatus(s.code())).isEqualTo("REFUNDED");
    }

    @Test
    void reconciliation_refundAlreadyInProgressAtVnpay_isNotRequestedTwice() {
        Scenario s = paidButSeatTakenByBob();
        jdbc.update("UPDATE payments SET updated_at = now() - interval '11 minutes' WHERE provider_txn_id = ?",
                s.txn());
        // Lần trước VNPay đã nhận yêu cầu (05 = đang hoàn tiền) nhưng mình không nhận được phản hồi
        when(vnPayApiClient.queryTransaction(eq(s.txn()), any())).thenReturn(query(s.txn(), "00", "05"));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.refunded()).isEqualTo(1);
        assertThat(paymentStatus(s.txn())).isEqualTo("REFUNDED");
        verify(vnPayApiClient, org.mockito.Mockito.never()).refund(any(), any(), any(), any());
    }

    // ===================== Đối soát giao dịch treo =====================

    @Test
    void lostIpn_reconciliationFindsPaymentAtVnpay_andConfirmsBooking() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        ageInitiatedPayment(txn, 16);
        when(vnPayApiClient.queryTransaction(eq(txn), any())).thenReturn(query(txn, "00", "00"));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(paymentStatus(txn)).isEqualTo("SUCCESS");
        assertThat(bookingStatus(code)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(a1)).isEqualTo("SOLD");
        assertThat(outboxCount(EventTypes.BOOKING_CONFIRMED)).isEqualTo(1);
    }

    @Test
    void transactionUnknownAtVnpay_failedOnlyAfterAbandonWindow() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String old = createPayment(alice, code);
        String recent = createPayment(alice, code);
        ageInitiatedPayment(old, 31);
        ageInitiatedPayment(recent, 16);
        when(vnPayApiClient.queryTransaction(any(), any())).thenAnswer(inv -> query(inv.getArgument(0), "91", null));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.stillPending()).isEqualTo(1);
        assertThat(paymentStatus(old)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT response_code FROM payments WHERE provider_txn_id = ?", String.class,
                old)).isEqualTo("91");
        assertThat(paymentStatus(recent)).isEqualTo("INITIATED");
    }

    @Test
    void gatewayError_leavesPaymentForNextRun() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        ageInitiatedPayment(txn, 16);
        when(vnPayApiClient.queryTransaction(eq(txn), any())).thenThrow(new VnPayApiException("timeout"));

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.errors()).isEqualTo(1);
        assertThat(paymentStatus(txn)).isEqualTo("INITIATED");
    }

    // ===================== helpers =====================

    /** Alice trả tiền sau khi hết giữ chỗ, ghế đã sang tay Bob → giao dịch REFUND_PENDING. */
    private Scenario paidButSeatTakenByBob() {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-key-0001", a1);
        String txn = createPayment(alice, code);
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE booking_code = ?", code);
        expiryService.expireDueBookings();
        holdBooking(customerToken("bob@test.vn"), fixture.showtimeId(), "bob-key-00001", a1);

        assertThat(sendIpn(signedVnPayParams(txn, PRICE, "00", "00"))).isEqualTo("00");
        assertThat(paymentStatus(txn)).isEqualTo("REFUND_PENDING");
        return new Scenario(code, txn);
    }

    private void ageInitiatedPayment(String txn, int minutes) {
        jdbc.update("UPDATE payments SET created_at = now() - make_interval(mins => ?) WHERE provider_txn_id = ?",
                minutes, txn);
    }

    private Instant createdAt(String txn) {
        return jdbc.queryForObject("SELECT created_at FROM payments WHERE provider_txn_id = ?", OffsetDateTime.class,
                txn).toInstant();
    }

    private int refundAttempts(String txn) {
        return jdbc.queryForObject("SELECT refund_attempts FROM payments WHERE provider_txn_id = ?", Integer.class,
                txn);
    }

    private static VnPayQueryResult query(String txn, String responseCode, String transactionStatus) {
        return new VnPayQueryResult(txn, responseCode, "", transactionStatus, "14422574", "NCB",
                BigDecimal.valueOf(PRICE), Instant.now(), Map.of());
    }

    private record Scenario(String code, String txn) {
    }
}
