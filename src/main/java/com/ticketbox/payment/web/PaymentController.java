package com.ticketbox.payment.web;

import com.ticketbox.auth.security.AuthUser;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.config.OpenApiConfig;
import com.ticketbox.common.web.ClientIp;
import com.ticketbox.payment.dto.PaymentDtos.CreatePaymentCommand;
import com.ticketbox.payment.dto.PaymentDtos.CreatePaymentRequest;
import com.ticketbox.payment.dto.PaymentDtos.PaymentReturnResponse;
import com.ticketbox.payment.dto.PaymentDtos.PaymentUrlResponse;
import com.ticketbox.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Payments", description = "Thanh toán VNPay: tạo link, IPN, Return URL")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "Tạo link thanh toán VNPay cho đơn đang giữ chỗ",
            description = "Client chuyển hướng người dùng tới paymentUrl. Link hết hạn cùng lúc với thời gian giữ ghế.")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentUrlResponse>> create(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {
        PaymentUrlResponse response = paymentService.createVnPayPayment(new CreatePaymentCommand(
                user.id(), user.isAdmin(), request.bookingCode(), request.bankCode(), ClientIp.of(httpRequest)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * VNPay gọi server-to-server (GET, tham số trên query string). Trả JSON đúng định dạng VNPay
     * ({"RspCode","Message"}), KHÔNG bọc ApiResponse. URL này phải truy cập được từ Internet
     * (khi chạy local: ngrok/cloudflared) và được khai báo trong trang quản trị merchant của VNPay.
     */
    @Operation(summary = "IPN: VNPay báo kết quả thanh toán (server-to-server, nguồn sự thật)")
    @GetMapping("/vnpay/ipn")
    public Map<String, String> ipn(@RequestParam Map<String, String> params) {
        return paymentService.handleVnPayIpn(params).toBody();
    }

    @Operation(summary = "Return URL: trình duyệt quay về sau thanh toán (chỉ hiển thị, không đổi trạng thái)")
    @GetMapping("/vnpay/return")
    public ApiResponse<PaymentReturnResponse> vnPayReturn(@RequestParam Map<String, String> params) {
        return ApiResponse.ok(paymentService.handleVnPayReturn(params));
    }
}
