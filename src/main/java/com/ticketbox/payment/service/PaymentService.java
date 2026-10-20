package com.ticketbox.payment.service;

import com.ticketbox.booking.api.BookingPaymentService;
import com.ticketbox.booking.api.PayableBooking;
import com.ticketbox.booking.domain.BookingStatus;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.payment.domain.Payment;
import com.ticketbox.payment.domain.PaymentLogKind;
import com.ticketbox.payment.domain.PaymentProvider;
import com.ticketbox.payment.domain.PaymentStatus;
import com.ticketbox.payment.dto.PaymentDtos.CreatePaymentCommand;
import com.ticketbox.payment.dto.PaymentDtos.PaymentReturnResponse;
import com.ticketbox.payment.dto.PaymentDtos.PaymentUrlResponse;
import com.ticketbox.payment.repository.PaymentRepository;
import com.ticketbox.payment.vnpay.PaymentUrlRequest;
import com.ticketbox.payment.vnpay.VnPayGateway;
import com.ticketbox.payment.vnpay.VnPayIpnResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Luồng thanh toán VNPay.
 *
 * <pre>
 * Client          Backend                              VNPay
 *   │ POST /payments │                                   │
 *   │───────────────▶│ payment INITIATED, ký HMAC-SHA512  │
 *   │◀── paymentUrl ─│                                   │
 *   │ redirect ──────────────────────────────────────────▶│ khách thanh toán
 *   │                │◀──── IPN (server-to-server) ───────│  ← NGUỒN SỰ THẬT
 *   │                │ xác minh chữ ký, số tiền, trạng thái│
 *   │                │ cập nhật DB + outbox (1 transaction)│
 *   │◀── Return URL (chỉ để hiển thị) ────────────────────│
 * </pre>
 */
@Slf4j
@Service
public class PaymentService {

    private static final char[] TXN_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final PaymentRepository paymentRepository;
    private final BookingPaymentService bookingPayments;
    private final VnPayGateway vnPayGateway;
    private final PaymentProcessor processor;
    private final PaymentLogWriter logWriter;
    private final PaymentProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final SecureRandom random = new SecureRandom();

    public PaymentService(PaymentRepository paymentRepository,
                          BookingPaymentService bookingPayments,
                          VnPayGateway vnPayGateway,
                          PaymentProcessor processor,
                          PaymentLogWriter logWriter,
                          PaymentProperties properties,
                          Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.bookingPayments = bookingPayments;
        this.vnPayGateway = vnPayGateway;
        this.processor = processor;
        this.logWriter = logWriter;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    // ===================================================================================
    // 1. Tạo link thanh toán
    // ===================================================================================

    /**
     * Mỗi lần gọi tạo một giao dịch mới (vnp_TxnRef mới): VNPay không cho dùng lại mã giao dịch, và khách có thể
     * thử lại sau khi lần trước thất bại. Link hết hạn đúng lúc hết giữ ghế (vnp_ExpireDate = expires_at của đơn).
     */
    public PaymentUrlResponse createVnPayPayment(CreatePaymentCommand command) {
        return tx.execute(status -> {
            PayableBooking booking = bookingPayments.findByCode(command.bookingCode())
                    .filter(b -> command.admin() || b.isOwnedBy(command.userId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.BOOKING_NOT_FOUND));
            if (booking.status() != BookingStatus.PENDING_PAYMENT) {
                throw new BusinessException(ErrorCode.BOOKING_NOT_PAYABLE);
            }
            Instant now = clock.instant();
            if (booking.expiresAt().isBefore(now.plus(properties.minRemainingHold()))) {
                throw new BusinessException(ErrorCode.BOOKING_EXPIRED);
            }
            if (paymentRepository.countByBookingId(booking.id()) >= properties.maxAttemptsPerBooking()) {
                throw new BusinessException(ErrorCode.PAYMENT_ATTEMPTS_EXCEEDED);
            }

            Payment payment = paymentRepository.saveAndFlush(Payment.initiate(booking.id(), PaymentProvider.VNPAY,
                    newTxnRef(booking.bookingCode()), booking.totalAmount()));
            String url = vnPayGateway.buildPaymentUrl(new PaymentUrlRequest(
                    payment.getTxnRef(),
                    payment.getAmount(),
                    "Thanh toan don ve " + booking.bookingCode(),
                    command.clientIp(),
                    payment.getCreatedAt(),     // lưu trong DB → dùng lại làm vnp_TransactionDate khi truy vấn/hoàn tiền
                    booking.expiresAt(),
                    command.bankCode()));
            log.info("Tạo giao dịch VNPay {} cho đơn {}, {} VND", payment.getTxnRef(), booking.bookingCode(),
                    payment.getAmount());
            return new PaymentUrlResponse(payment.getTxnRef(), url, payment.getAmount(), booking.expiresAt());
        });
    }

    // ===================================================================================
    // 2. IPN: nguồn sự thật
    // ===================================================================================

    /**
     * Thứ tự kiểm tra theo đặc tả VNPay: chữ ký (97) → giao dịch tồn tại (01) → số tiền (04)
     * → đã xử lý chưa (02) → cập nhật (00). Mọi lỗi không lường trước trả 99 để VNPay gọi lại sau.
     * Không bao giờ ném exception ra ngoài: VNPay cần nhận đúng định dạng JSON.
     */
    public VnPayIpnResponse handleVnPayIpn(Map<String, String> params) {
        String txnRef = params.get(VnPayGateway.TXN_REF);
        VnPayIpnResponse response;
        if (!vnPayGateway.verifySignature(params)) {
            response = VnPayIpnResponse.INVALID_SIGNATURE;
            log.warn("IPN sai chữ ký, txnRef={}", txnRef);
        } else {
            try {
                response = tx.execute(status -> processIpn(params));
            } catch (RuntimeException e) {
                log.error("Xử lý IPN {} lỗi, trả 99 để VNPay gọi lại", txnRef, e);
                response = VnPayIpnResponse.UNKNOWN_ERROR;
            }
        }
        logWriter.log(PaymentProvider.VNPAY, PaymentLogKind.IPN, txnRef, params, response.rspCode());
        return response;
    }

    private VnPayIpnResponse processIpn(Map<String, String> params) {
        GatewayResult result = vnPayGateway.parseCallback(params);
        // Khóa dòng payment: IPN lặp lại / song song với job đối soát phải xếp hàng tại đây
        Optional<Payment> found = paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, result.txnRef());
        if (found.isEmpty()) {
            return VnPayIpnResponse.ORDER_NOT_FOUND;
        }
        Payment payment = found.get();
        if (result.amount().compareTo(payment.getAmount()) != 0) {
            log.error("IPN {} sai số tiền: VNPay {} ≠ hệ thống {}", payment.getTxnRef(), result.amount(),
                    payment.getAmount());
            return VnPayIpnResponse.INVALID_AMOUNT;
        }
        if (!payment.isInitiated()) {
            return VnPayIpnResponse.ALREADY_CONFIRMED;   // IPN gửi lại: idempotent, không xử lý lần 2
        }
        ProcessOutcome outcome = processor.apply(payment, result);
        log.info("IPN {}: {}", payment.getTxnRef(), outcome);
        return VnPayIpnResponse.SUCCESS;
    }

    // ===================================================================================
    // 3. Return URL: chỉ hiển thị
    // ===================================================================================

    /**
     * Trình duyệt quay về sau khi thanh toán. KHÔNG cập nhật trạng thái (người dùng có thể sửa URL, hoặc đóng tab
     * trước khi tới). Nếu IPN chưa tới, trả trạng thái "đang xử lý" để client hỏi lại GET /bookings/{code}.
     */
    public PaymentReturnResponse handleVnPayReturn(Map<String, String> params) {
        String txnRef = params.get(VnPayGateway.TXN_REF);
        boolean validSignature = vnPayGateway.verifySignature(params);
        logWriter.log(PaymentProvider.VNPAY, PaymentLogKind.RETURN, txnRef, params, validSignature ? "OK" : "97");
        if (!validSignature) {
            throw new BusinessException(ErrorCode.PAYMENT_SIGNATURE_INVALID);
        }
        GatewayResult result;
        try {
            result = vnPayGateway.parseCallback(params);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST);
        }
        return readOnlyTx.execute(status -> {
            Payment payment = paymentRepository.findByProviderAndTxnRef(PaymentProvider.VNPAY, result.txnRef())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
            PayableBooking booking = bookingPayments.findById(payment.getBookingId()).orElseThrow();
            return new PaymentReturnResponse(payment.getTxnRef(), booking.bookingCode(),
                    payment.getStatus().name(), booking.status().name(), result.responseCode(), result.paid(),
                    returnMessage(payment.getStatus(), booking.status(), result));
        });
    }

    private static String returnMessage(PaymentStatus paymentStatus, BookingStatus bookingStatus,
                                        GatewayResult result) {
        if (paymentStatus == PaymentStatus.SUCCESS && bookingStatus == BookingStatus.CONFIRMED) {
            return "Thanh toán thành công. Vé đã được gửi tới email của bạn.";
        }
        if (paymentStatus == PaymentStatus.REFUND_PENDING || paymentStatus == PaymentStatus.REFUNDED) {
            return "Rất tiếc, ghế đã không còn giữ được. Chúng tôi đang hoàn tiền cho bạn.";
        }
        if (paymentStatus == PaymentStatus.INITIATED && result.paid()) {
            return "Đang xác nhận thanh toán, vui lòng chờ trong giây lát.";
        }
        if ("24".equals(result.responseCode())) {
            return "Bạn đã hủy giao dịch.";
        }
        return "Thanh toán không thành công. Bạn có thể thử lại trong thời gian giữ ghế.";
    }

    /** Mã đơn + 6 ký tự ngẫu nhiên, chỉ gồm chữ và số (yêu cầu của VNPay). Unique constraint là chốt chặn cuối. */
    private String newTxnRef(String bookingCode) {
        StringBuilder sb = new StringBuilder(bookingCode);
        for (int i = 0; i < 6; i++) {
            sb.append(TXN_ALPHABET[random.nextInt(TXN_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
