package com.ticketbox.payment.service;

import com.ticketbox.booking.api.BookingPaymentService;
import com.ticketbox.booking.api.PayableBooking;
import com.ticketbox.booking.domain.BookingStatus;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.payment.domain.Payment;
import com.ticketbox.payment.domain.PaymentLogKind;
import com.ticketbox.payment.domain.PaymentProvider;
import com.ticketbox.payment.dto.PaymentDtos.CreatePaymentCommand;
import com.ticketbox.payment.dto.PaymentDtos.PaymentUrlResponse;
import com.ticketbox.payment.repository.PaymentRepository;
import com.ticketbox.payment.vnpay.VnPayGateway;
import com.ticketbox.payment.vnpay.VnPayIpnResponse;
import com.ticketbox.payment.vnpay.VnPaySigner;
import com.ticketbox.payment.vnpay.VnPayTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Các nhánh xử lý IPN và tạo link thanh toán (không DB). Luồng đầy đủ: PaymentFlowIT. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
    private static final String TXN = "TBTESTCODE01ABCDEF";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingPaymentService bookingPayments;
    @Mock
    private PaymentProcessor processor;
    @Mock
    private PaymentLogWriter logWriter;
    @Mock
    private PlatformTransactionManager transactionManager;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = VnPayTestData.properties();
        service = new PaymentService(paymentRepository, bookingPayments, new VnPayGateway(properties), processor,
                logWriter, properties, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    // ===================== IPN =====================

    @Test
    void ipn_invalidSignature_returns97_withoutTouchingDatabase() {
        Map<String, String> params = signedIpn(180000, "00");
        params.put("vnp_Amount", "100");

        assertThat(service.handleVnPayIpn(params)).isEqualTo(VnPayIpnResponse.INVALID_SIGNATURE);
        verifyNoInteractions(paymentRepository, processor);
        verify(logWriter).log(eq(PaymentProvider.VNPAY), eq(PaymentLogKind.IPN), eq(TXN), any(), eq("97"));
    }

    @Test
    void ipn_unknownTransaction_returns01() {
        when(paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, TXN)).thenReturn(Optional.empty());

        assertThat(service.handleVnPayIpn(signedIpn(180000, "00"))).isEqualTo(VnPayIpnResponse.ORDER_NOT_FOUND);
    }

    @Test
    void ipn_amountMismatch_returns04_andDoesNotProcess() {
        when(paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, TXN))
                .thenReturn(Optional.of(initiated("180000")));

        assertThat(service.handleVnPayIpn(signedIpn(1000, "00"))).isEqualTo(VnPayIpnResponse.INVALID_AMOUNT);
        verifyNoInteractions(processor);
    }

    @Test
    void ipn_alreadyProcessed_returns02_idempotent() {
        Payment payment = initiated("180000");
        payment.markFailed(new Payment.GatewayData("24", null, null, null, Map.of()));
        when(paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, TXN)).thenReturn(Optional.of(payment));

        assertThat(service.handleVnPayIpn(signedIpn(180000, "00"))).isEqualTo(VnPayIpnResponse.ALREADY_CONFIRMED);
        verifyNoInteractions(processor);
    }

    @Test
    void ipn_valid_processesPaidResult_andReturns00() {
        Payment payment = initiated("180000");
        when(paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, TXN)).thenReturn(Optional.of(payment));
        when(processor.apply(any(), any())).thenReturn(ProcessOutcome.CONFIRMED);

        assertThat(service.handleVnPayIpn(signedIpn(180000, "00"))).isEqualTo(VnPayIpnResponse.SUCCESS);

        ArgumentCaptor<GatewayResult> result = ArgumentCaptor.forClass(GatewayResult.class);
        verify(processor).apply(eq(payment), result.capture());
        assertThat(result.getValue().paid()).isTrue();
        assertThat(result.getValue().transactionNo()).isEqualTo("14422574");
    }

    @Test
    void ipn_unexpectedError_returns99_soVnpayRetries() {
        when(paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, TXN))
                .thenThrow(new IllegalStateException("DB down"));

        assertThat(service.handleVnPayIpn(signedIpn(180000, "00"))).isEqualTo(VnPayIpnResponse.UNKNOWN_ERROR);
    }

    // ===================== Tạo link thanh toán =====================

    @Test
    void createPayment_forOwnPendingBooking_returnsSignedUrl() {
        when(bookingPayments.findByCode("TBTESTCODE01")).thenReturn(Optional.of(booking(BookingStatus.PENDING_PAYMENT,
                NOW.plusSeconds(540))));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "createdAt", NOW);   // auditing không chạy trong unit test
            return p;
        });

        PaymentUrlResponse response = service.createVnPayPayment(command(7L));

        assertThat(response.txnRef()).startsWith("TBTESTCODE01").hasSize(18);
        assertThat(response.paymentUrl()).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?")
                .contains("vnp_TxnRef=" + response.txnRef())
                .contains("vnp_Amount=18000000")
                .contains("vnp_ExpireDate=20261020100900")
                .contains("vnp_SecureHash=");
    }

    @Test
    void createPayment_otherUsersBooking_isNotFound() {
        when(bookingPayments.findByCode("TBTESTCODE01"))
                .thenReturn(Optional.of(booking(BookingStatus.PENDING_PAYMENT, NOW.plusSeconds(540))));

        assertThatThrownBy(() -> service.createVnPayPayment(command(99L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
    }

    @Test
    void createPayment_bookingNotPending_isRejected() {
        when(bookingPayments.findByCode("TBTESTCODE01"))
                .thenReturn(Optional.of(booking(BookingStatus.CONFIRMED, NOW.plusSeconds(540))));

        assertThatThrownBy(() -> service.createVnPayPayment(command(7L)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.BOOKING_NOT_PAYABLE);
    }

    @Test
    void createPayment_holdAboutToExpire_isRejected() {
        when(bookingPayments.findByCode("TBTESTCODE01"))
                .thenReturn(Optional.of(booking(BookingStatus.PENDING_PAYMENT, NOW.plusSeconds(30))));

        assertThatThrownBy(() -> service.createVnPayPayment(command(7L)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.BOOKING_EXPIRED);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    // ===================== helpers =====================

    private static CreatePaymentCommand command(Long userId) {
        return new CreatePaymentCommand(userId, false, "TBTESTCODE01", null, "127.0.0.1");
    }

    private static PayableBooking booking(BookingStatus status, Instant expiresAt) {
        return new PayableBooking(1L, "TBTESTCODE01", 7L, "a@test.vn", status, new BigDecimal("180000"), expiresAt);
    }

    private static Payment initiated(String amount) {
        Payment payment = Payment.initiate(1L, PaymentProvider.VNPAY, TXN, new BigDecimal(amount));
        ReflectionTestUtils.setField(payment, "id", 10L);
        return payment;
    }

    private static Map<String, String> signedIpn(long amountVnd, String responseCode) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TmnCode", "TESTTMN1");
        params.put("vnp_Amount", String.valueOf(amountVnd * 100));
        params.put("vnp_TxnRef", TXN);
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", responseCode);
        params.put("vnp_TransactionNo", "14422574");
        params.put("vnp_PayDate", "20261020100512");
        params.put("vnp_SecureHash", VnPaySigner.hmacSha512(VnPayTestData.SECRET, VnPaySigner.canonicalQuery(params)));
        return params;
    }
}
