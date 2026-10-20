package com.ticketbox.payment.service;

import com.ticketbox.payment.domain.Payment;
import com.ticketbox.payment.domain.PaymentProvider;
import com.ticketbox.payment.domain.PaymentStatus;
import com.ticketbox.payment.dto.PaymentDtos.ReconciliationReport;
import com.ticketbox.payment.repository.PaymentRepository;
import com.ticketbox.payment.vnpay.VnPayApiClient;
import com.ticketbox.payment.vnpay.VnPayQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * ĐỐI SOÁT: lưới an toàn khi IPN bị mất (server sập đúng lúc VNPay gọi, VNPay gọi đủ 10 lần đều lỗi...).
 *
 * <pre>
 * Giao dịch INITIATED quá 15 phút → hỏi VNPay (querydr)
 *   ├─ đã thanh toán            → xử lý y như IPN (cùng PaymentProcessor)
 *   ├─ lỗi / giao dịch đảo       → FAILED
 *   ├─ chưa hoàn tất / không có  → quá 30 phút thì FAILED (khách bỏ dở), chưa thì chờ lượt sau
 *   └─ lỗi gọi API               → bỏ qua, lượt sau thử lại
 *
 * Giao dịch REFUND_PENDING không đổi quá 10 phút → hỏi VNPay
 *   ├─ đang/đã hoàn tiền (05, 06) → chốt REFUNDED
 *   └─ chưa                      → gọi API hoàn tiền lại (tối đa 5 lần, sau đó chờ xử lý tay)
 * </pre>
 * Gọi API NGOÀI transaction; chỉ khóa dòng payment khi ghi kết quả, và kiểm tra lại trạng thái
 * (IPN có thể tới đúng lúc đang truy vấn). Chạy nhiều instance song song vẫn đúng, chỉ tốn thêm lượt truy vấn.
 */
@Slf4j
@Service
public class PaymentReconciliationService {

    private final PaymentRepository paymentRepository;
    private final VnPayApiClient vnPayApiClient;
    private final PaymentProcessor processor;
    private final RefundService refundService;
    private final PaymentProperties.Reconciliation config;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public PaymentReconciliationService(PaymentRepository paymentRepository, VnPayApiClient vnPayApiClient,
                                        PaymentProcessor processor, RefundService refundService,
                                        PaymentProperties properties, Clock clock,
                                        PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.vnPayApiClient = vnPayApiClient;
        this.processor = processor;
        this.refundService = refundService;
        this.config = properties.reconciliation();
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public ReconciliationReport reconcile() {
        Instant now = clock.instant();
        Counters counters = new Counters();

        List<String> stale = readOnlyTx.execute(status -> paymentRepository.findStaleInitiated(
                PaymentProvider.VNPAY, now.minus(config.initiatedAfter()), PageRequest.of(0, config.batchSize())));
        for (String txnRef : stale == null ? List.<String>of() : stale) {
            counters.checked++;
            try {
                counters.count(reconcileInitiated(txnRef, now));
            } catch (RuntimeException e) {
                counters.errors++;
                log.warn("Đối soát giao dịch {} lỗi, lượt sau thử lại: {}", txnRef, e.toString());
            }
        }

        List<String> refunds = readOnlyTx.execute(status -> paymentRepository.findStaleRefunds(
                PaymentProvider.VNPAY, now.minus(config.refundRetryAfter()), config.maxRefundAttempts(),
                PageRequest.of(0, config.batchSize())));
        for (String txnRef : refunds == null ? List.<String>of() : refunds) {
            counters.checked++;
            try {
                if (retryRefund(txnRef)) {
                    counters.refunded++;
                }
            } catch (RuntimeException e) {
                counters.errors++;
                log.error("Hoàn tiền lại giao dịch {} lỗi: {}", txnRef, e.toString());
            }
        }

        ReconciliationReport report = counters.toReport();
        if (report.checked() > 0) {
            log.info("Đối soát thanh toán: {}", report);
        }
        return report;
    }

    /** @return null nếu vẫn chờ */
    ProcessOutcome reconcileInitiated(String txnRef, Instant now) {
        Instant createdAt = readOnlyTx.execute(status -> paymentRepository
                .findByProviderAndTxnRef(PaymentProvider.VNPAY, txnRef)
                .filter(Payment::isInitiated)
                .map(Payment::getCreatedAt)
                .orElse(null));
        if (createdAt == null) {
            return null;
        }
        VnPayQueryResult query = vnPayApiClient.queryTransaction(txnRef, createdAt);

        return tx.execute(status -> {
            Payment payment = paymentRepository.findByTxnRefForUpdate(PaymentProvider.VNPAY, txnRef).orElse(null);
            if (payment == null || !payment.isInitiated()) {
                return null;                                   // IPN vừa tới trong lúc truy vấn
            }
            if (query.paid()) {
                if (query.amount() == null || query.amount().compareTo(payment.getAmount()) != 0) {
                    log.error("Đối soát {}: số tiền VNPay {} ≠ hệ thống {}, cần kiểm tra tay", txnRef,
                            query.amount(), payment.getAmount());
                    return null;
                }
                log.warn("Đối soát {}: VNPay báo ĐÃ thanh toán nhưng hệ thống chưa nhận IPN → xử lý bù", txnRef);
                return processor.apply(payment, query.toGatewayResult());
            }
            boolean abandoned = payment.getCreatedAt().isBefore(now.minus(config.abandonAfter()));
            if (query.failed() || (abandoned && (query.notFound() || query.incomplete()))) {
                return processor.apply(payment, query.toGatewayResult());
            }
            return null;
        });
    }

    private boolean retryRefund(String txnRef) {
        Instant createdAt = readOnlyTx.execute(status -> paymentRepository
                .findByProviderAndTxnRef(PaymentProvider.VNPAY, txnRef)
                .filter(p -> p.getStatus() == PaymentStatus.REFUND_PENDING)
                .map(Payment::getCreatedAt)
                .orElse(null));
        if (createdAt == null) {
            return false;
        }
        // Lần trước có thể VNPay đã nhận yêu cầu nhưng mình không nhận được phản hồi: hỏi trước, tránh hoàn 2 lần
        VnPayQueryResult query = vnPayApiClient.queryTransaction(txnRef, createdAt);
        if (query.refundInProgressOrDone()) {
            return refundService.completeRefund(txnRef);
        }
        return refundService.refund(txnRef);
    }

    private static final class Counters {
        int checked;
        int confirmed;
        int failed;
        int refundRequired;
        int refunded;
        int stillPending;
        int errors;

        void count(ProcessOutcome outcome) {
            if (outcome == null) {
                stillPending++;
                return;
            }
            switch (outcome) {
                case CONFIRMED, CONFIRMED_LATE -> confirmed++;
                case REFUND_REQUIRED -> refundRequired++;
                case FAILED -> failed++;
            }
        }

        ReconciliationReport toReport() {
            return new ReconciliationReport(checked, confirmed, failed, refundRequired, refunded, stillPending,
                    errors);
        }
    }
}
