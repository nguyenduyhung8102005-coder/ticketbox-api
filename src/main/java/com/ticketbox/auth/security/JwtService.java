package com.ticketbox.auth.security;

import com.ticketbox.user.domain.Role;
import com.ticketbox.user.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Phát hành và xác minh access token (JWT ký HMAC-SHA256).
 * Token mang sẵn id, email, role nên filter không cần truy vấn DB ở mỗi request.
 */
@Component
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issueAccessToken(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Xác minh chữ ký, issuer, hạn dùng và loại token.
     *
     * @throws JwtException nếu token không hợp lệ hoặc đã hết hạn
     */
    public AuthUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("Không phải access token");
        }
        try {
            return new AuthUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class)),
                    claims.getId(),
                    claims.getExpiration().toInstant());
        } catch (RuntimeException e) {
            throw new JwtException("Access token thiếu hoặc sai claim", e);
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
