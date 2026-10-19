package com.ticketbox.auth.security;

import com.ticketbox.user.domain.Role;

import java.time.Instant;

/**
 * Người dùng đang đăng nhập, dựng từ access token (không truy vấn DB ở mỗi request).
 * Lấy trong controller bằng {@code @AuthenticationPrincipal AuthUser user}.
 *
 * @param tokenId        claim "jti", dùng để đưa token vào blacklist khi logout
 * @param tokenExpiresAt thời điểm token hết hạn, quyết định TTL của bản ghi blacklist
 */
public record AuthUser(Long id, String email, Role role, String tokenId, Instant tokenExpiresAt) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
