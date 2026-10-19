package com.ticketbox.auth.web;

import com.ticketbox.auth.AuthService;
import com.ticketbox.auth.dto.AuthDtos.AuthResponse;
import com.ticketbox.auth.dto.AuthDtos.LoginRequest;
import com.ticketbox.auth.dto.AuthDtos.LogoutRequest;
import com.ticketbox.auth.dto.AuthDtos.RefreshRequest;
import com.ticketbox.auth.dto.AuthDtos.RegisterRequest;
import com.ticketbox.auth.security.AuthUser;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.config.OpenApiConfig;
import com.ticketbox.user.web.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Đăng ký, đăng nhập, làm mới token, đăng xuất")
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Đăng ký tài khoản khách hàng")
    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(authService.register(request)));
    }

    @Operation(summary = "Đăng nhập, nhận access token (15 phút) và refresh token (7 ngày)")
    @PostMapping("/auth/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @Operation(summary = "Đổi refresh token lấy cặp token mới (refresh token cũ hết hiệu lực)")
    @PostMapping("/auth/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "Đăng xuất: thu hồi access token hiện tại và refresh token (nếu gửi kèm)")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal AuthUser user,
                                    @RequestBody(required = false) LogoutRequest request) {
        authService.logout(user, request == null ? null : request.refreshToken());
        return ApiResponse.ok();
    }

    @Operation(summary = "Thông tin tài khoản đang đăng nhập")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@Parameter(hidden = true) @AuthenticationPrincipal AuthUser user) {
        return ApiResponse.ok(authService.currentUser(user.id()));
    }
}
