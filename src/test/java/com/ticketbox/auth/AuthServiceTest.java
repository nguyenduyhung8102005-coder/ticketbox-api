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
import com.ticketbox.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    // BCrypt thật với strength thấp cho nhanh: kiểm tra được cả việc băm và so khớp mật khẩu
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private TokenBlacklistService tokenBlacklist;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenStore, tokenBlacklist);
    }

    @Test
    void register_newEmail_storesNormalizedEmailAndBcryptHash() {
        when(userRepository.existsByEmail("an@gmail.com")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0), 7L));
        stubTokenIssuing();

        AuthResponse response = authService.register(
                new RegisterRequest("  An@Gmail.com ", "MatKhau@123", " Nguyễn An ", "0912345678"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("an@gmail.com");
        assertThat(saved.getValue().getFullName()).isEqualTo("Nguyễn An");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("MatKhau@123");
        assertThat(passwordEncoder.matches("MatKhau@123", saved.getValue().getPasswordHash())).isTrue();

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.user().id()).isEqualTo(7L);
    }

    @Test
    void register_existingEmail_isRejected() {
        when(userRepository.existsByEmail("an@gmail.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("an@gmail.com", "MatKhau@123", "An", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_existingPhone_isRejected() {
        when(userRepository.existsByEmail("an@gmail.com")).thenReturn(false);
        when(userRepository.existsByPhone("0912345678")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("an@gmail.com", "MatKhau@123", "An", "0912345678")))
                .extracting("errorCode").isEqualTo(ErrorCode.PHONE_ALREADY_EXISTS);
    }

    @Test
    void login_correctPassword_issuesTokens() {
        User user = withId(User.create("an@gmail.com", null, passwordEncoder.encode("MatKhau@123"), "An",
                Role.CUSTOMER), 7L);
        when(userRepository.findByEmail("an@gmail.com")).thenReturn(Optional.of(user));
        stubTokenIssuing();

        AuthResponse response = authService.login(new LoginRequest("AN@gmail.com", "MatKhau@123"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
    }

    @Test
    void login_wrongPassword_returnsGenericError() {
        User user = User.create("an@gmail.com", null, passwordEncoder.encode("MatKhau@123"), "An", Role.CUSTOMER);
        when(userRepository.findByEmail("an@gmail.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("an@gmail.com", "sai-mat-khau")))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void login_unknownEmail_returnsSameErrorAsWrongPassword() {
        when(userRepository.findByEmail("ghost@gmail.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@gmail.com", "MatKhau@123")))
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void login_lockedAccount_isRejected() {
        User user = User.create("an@gmail.com", null, passwordEncoder.encode("MatKhau@123"), "An", Role.CUSTOMER);
        ReflectionTestUtils.setField(user, "status", UserStatus.LOCKED);
        when(userRepository.findByEmail("an@gmail.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("an@gmail.com", "MatKhau@123")))
                .extracting("errorCode").isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void refresh_unknownOrReusedToken_isRejected() {
        when(refreshTokenStore.consume("old-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("old-token"))
                .extracting("errorCode").isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void refresh_validToken_issuesNewPair() {
        User user = withId(User.create("an@gmail.com", null, "hash", "An", Role.CUSTOMER), 7L);
        when(refreshTokenStore.consume("valid-token")).thenReturn(Optional.of(7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        stubTokenIssuing();

        assertThat(authService.refresh("valid-token").refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void logout_revokesAccessTokenAndRefreshToken() {
        Instant exp = Instant.parse("2026-10-19T10:15:00Z");
        AuthUser current = new AuthUser(7L, "an@gmail.com", Role.CUSTOMER, "jti-1", exp);

        authService.logout(current, "refresh-token");

        verify(tokenBlacklist).revoke("jti-1", exp);
        verify(refreshTokenStore).revoke("refresh-token", 7L);
    }

    private void stubTokenIssuing() {
        when(jwtService.issueAccessToken(any(User.class)))
                .thenReturn(new JwtService.IssuedToken("access-token", Instant.parse("2026-10-19T10:15:00Z")));
        when(jwtService.accessTokenTtlSeconds()).thenReturn(900L);
        when(refreshTokenStore.issue(any())).thenReturn("refresh-token");
        when(refreshTokenStore.ttlSeconds()).thenReturn(604800L);
    }

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
