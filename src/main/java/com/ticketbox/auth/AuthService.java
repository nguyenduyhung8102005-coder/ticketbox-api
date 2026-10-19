package com.ticketbox.auth;

import com.ticketbox.auth.dto.AuthDtos.AuthResponse;
import com.ticketbox.auth.dto.AuthDtos.LoginRequest;
import com.ticketbox.auth.dto.AuthDtos.RegisterRequest;
import com.ticketbox.auth.security.AuthUser;
import com.ticketbox.auth.security.JwtService;
import com.ticketbox.auth.security.RefreshTokenStore;
import com.ticketbox.auth.security.TokenBlacklistService;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.user.domain.Role;
import com.ticketbox.user.domain.User;
import com.ticketbox.user.domain.UserRepository;
import com.ticketbox.user.web.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenBlacklistService tokenBlacklist;

    /**
     * Hash giả dùng khi email không tồn tại: vẫn chạy BCrypt một lần để thời gian phản hồi giống
     * trường hợp sai mật khẩu, kẻ tấn công không dò được email nào đã đăng ký (timing attack).
     */
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenStore refreshTokenStore,
                       TokenBlacklistService tokenBlacklist) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.tokenBlacklist = tokenBlacklist;
        this.dummyHash = passwordEncoder.encode("ticketbox-timing-dummy-password");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String phone = blankToNull(request.phone());

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS);
        }
        // Hai request đăng ký cùng email đồng thời: unique index chặn ở DB,
        // GlobalExceptionHandler chuyển lỗi uk_users_email_lower thành EMAIL_ALREADY_EXISTS.
        User user = userRepository.save(User.create(
                email, phone, passwordEncoder.encode(request.password()), request.fullName().trim(), Role.CUSTOMER));
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        return issueTokens(user);
    }

    /** Đổi refresh token cũ lấy cặp token mới. Token cũ bị xóa ngay (rotation). */
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        Long userId = refreshTokenStore.consume(refreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        return issueTokens(user);
    }

    public void logout(AuthUser currentUser, String refreshToken) {
        tokenBlacklist.revoke(currentUser.tokenId(), currentUser.tokenExpiresAt());
        refreshTokenStore.revoke(refreshToken, currentUser.id());
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private AuthResponse issueTokens(User user) {
        JwtService.IssuedToken access = jwtService.issueAccessToken(user);
        String refresh = refreshTokenStore.issue(user.getId());
        return new AuthResponse(
                access.value(),
                refresh,
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                refreshTokenStore.ttlSeconds(),
                UserResponse.from(user));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
