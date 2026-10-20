package com.ticketbox.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbox.payment.domain.PaymentLogKind;
import com.ticketbox.payment.domain.PaymentProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ghi nguyên văn mọi lần trao đổi với cổng thanh toán vào payment_logs.
 * <p>
 * Chạy trong transaction RIÊNG (REQUIRES_NEW): nhật ký vẫn được lưu kể cả khi xử lý nghiệp vụ rollback
 * (ví dụ IPN chữ ký sai, lỗi DB giữa chừng) - chính những lúc đó mới cần nhật ký để tra soát.
 * Lỗi khi ghi nhật ký không bao giờ được làm hỏng luồng thanh toán: chỉ ghi log cảnh báo.
 */
@Slf4j
@Component
public class PaymentLogWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public PaymentLogWriter(JdbcTemplate jdbc, ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void log(PaymentProvider provider, PaymentLogKind kind, String txnRef, Object payload, String result) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            requiresNew.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO payment_logs (payment_id, provider, kind, txn_ref, payload, result)
                    VALUES ((SELECT id FROM payments WHERE provider = ? AND provider_txn_id = ?),
                            ?, ?, ?, ?::jsonb, ?)""",
                    provider.name(), txnRef, provider.name(), kind.name(), txnRef, json, result));
        } catch (Exception e) {
            log.warn("Không ghi được payment_logs ({} {}): {}", kind, txnRef, e.toString());
        }
    }
}
