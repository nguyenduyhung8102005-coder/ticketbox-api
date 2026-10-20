package com.ticketbox.payment.web;

import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.config.OpenApiConfig;
import com.ticketbox.payment.dto.PaymentDtos.ReconciliationReport;
import com.ticketbox.payment.service.PaymentReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Payments", description = "Đối soát thanh toán")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/payments")
public class AdminPaymentController {

    private final PaymentReconciliationService reconciliationService;

    public AdminPaymentController(PaymentReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Operation(summary = "Chạy đối soát ngay (bình thường job tự chạy mỗi 5 phút)")
    @PostMapping("/reconcile")
    public ApiResponse<ReconciliationReport> reconcile() {
        return ApiResponse.ok(reconciliationService.reconcile());
    }
}
