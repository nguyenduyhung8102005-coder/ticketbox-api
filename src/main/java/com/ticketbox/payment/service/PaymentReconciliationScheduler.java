package com.ticketbox.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Chạy đối soát mỗi 5 phút. Tắt bằng app.payment.reconciliation.enabled=false (profile test). */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.payment.reconciliation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliationService;

    public PaymentReconciliationScheduler(PaymentReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${app.payment.reconciliation.interval:PT5M}", initialDelayString = "PT1M")
    public void run() {
        try {
            reconciliationService.reconcile();
        } catch (RuntimeException e) {
            log.error("Job đối soát thanh toán lỗi", e);
        }
    }
}
