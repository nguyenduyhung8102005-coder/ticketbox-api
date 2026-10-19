package com.ticketbox.auth.security;

import com.ticketbox.user.domain.Role;
import com.ticketbox.user.domain.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final JwtProperties properties =
            new JwtProperties(SECRET, "ticketbox-api", Duration.ofMinutes(15), Duration.ofDays(7));

    @Test
    void issuedToken_canBeParsedBack() {
        JwtService service = serviceAt(NOW);

        JwtService.IssuedToken token = service.issueAccessToken(user(42L, Role.ADMIN));
        AuthUser parsed = service.parseAccessToken(token.value());

        assertThat(parsed.id()).isEqualTo(42L);
        assertThat(parsed.email()).isEqualTo("user42@test.vn");
        assertThat(parsed.role()).isEqualTo(Role.ADMIN);
        assertThat(parsed.isAdmin()).isTrue();
        assertThat(parsed.tokenId()).isNotBlank();
        assertThat(parsed.tokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(token.expiresAt()).isEqualTo(parsed.tokenExpiresAt());
    }

    @Test
    void eachToken_hasUniqueId_soLogoutRevokesOnlyThatToken() {
        JwtService service = serviceAt(NOW);
        User user = user(1L, Role.CUSTOMER);

        String first = service.parseAccessToken(service.issueAccessToken(user).value()).tokenId();
        String second = service.parseAccessToken(service.issueAccessToken(user).value()).tokenId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void expiredToken_isRejected() {
        String token = serviceAt(NOW).issueAccessToken(user(1L, Role.CUSTOMER)).value();

        JwtService sixteenMinutesLater = serviceAt(NOW.plus(Duration.ofMinutes(16)));

        assertThatThrownBy(() -> sixteenMinutesLater.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedToken_isRejected() {
        String token = serviceAt(NOW).issueAccessToken(user(1L, Role.CUSTOMER)).value();
        String[] parts = token.split("\\.");
        // Sửa payload (ví dụ đổi role) mà không có khóa thì chữ ký không còn khớp
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];

        assertThatThrownBy(() -> serviceAt(NOW).parseAccessToken(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecret_isRejected() {
        JwtProperties other = new JwtProperties("another-secret-0123456789abcdef-0123456789",
                "ticketbox-api", Duration.ofMinutes(15), Duration.ofDays(7));
        String foreign = new JwtService(other, Clock.fixed(NOW, ZoneOffset.UTC))
                .issueAccessToken(user(1L, Role.ADMIN)).value();

        assertThatThrownBy(() -> serviceAt(NOW).parseAccessToken(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecret_failsFastAtStartup() {
        assertThatThrownBy(() -> new JwtProperties("too-short", "iss", Duration.ofMinutes(15), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class);
    }

    private JwtService serviceAt(Instant instant) {
        return new JwtService(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static User user(Long id, Role role) {
        User user = User.create("user" + id + "@test.vn", null, "hash", "User " + id, role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
