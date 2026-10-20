package com.ticketbox.booking.web;

import com.ticketbox.booking.ticket.TicketVerificationService;
import com.ticketbox.booking.ticket.TicketVerificationService.TicketVerifyResponse;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Tickets", description = "Soát vé")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/tickets")
public class AdminTicketController {

    private final TicketVerificationService verificationService;

    public AdminTicketController(TicketVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Operation(summary = "Xác minh mã vé (nội dung QR): chữ ký HMAC + trạng thái đơn",
            description = "Luôn trả 200; xem trường valid/result. Vé giả hoặc bị sửa: SIGNATURE_INVALID.")
    @PostMapping("/verify")
    public ApiResponse<TicketVerifyResponse> verify(@Valid @RequestBody VerifyTicketRequest request) {
        return ApiResponse.ok(verificationService.verify(request.ticketCode()));
    }

    public record VerifyTicketRequest(
            @NotBlank @Size(max = 128) @Schema(example = "TBK1.MTIzLjQ1LjY3OA.q2Jx0n8yZ6m1o3VbX9kQeA")
            String ticketCode) {
    }
}
