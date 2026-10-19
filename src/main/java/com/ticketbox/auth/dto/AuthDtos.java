package com.ticketbox.auth.dto;

import com.ticketbox.user.web.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Các DTO của module auth, gom chung một file cho gọn. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255)
            @Schema(example = "an.nguyen@gmail.com")
            String email,

            // BCrypt chỉ dùng 72 byte đầu, giới hạn để người dùng không tưởng mật khẩu dài hơn là an toàn hơn
            @NotBlank @Size(min = 8, max = 72)
            @Schema(example = "MatKhau@123")
            String password,

            @NotBlank @Size(max = 150)
            @Schema(example = "Nguyễn Văn An")
            String fullName,

            @Pattern(regexp = "^(0|\\+84)\\d{9,10}$", message = "Số điện thoại không hợp lệ")
            @Schema(example = "0912345678")
            String phone) {
    }

    public record LoginRequest(
            @NotBlank @Email @Schema(example = "an.nguyen@gmail.com") String email,
            @NotBlank @Schema(example = "MatKhau@123") String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** refreshToken không bắt buộc; có thì thu hồi luôn để không xin được access token mới. */
    public record LogoutRequest(String refreshToken) {
    }

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            long refreshExpiresIn,
            UserResponse user) {
    }
}
