package com.ticketbox.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cấu hình JWT (prefix app.jwt).
 *
 * @param secret          khóa ký HMAC, tối thiểu 32 byte (256 bit) cho HS256. Production truyền qua JWT_SECRET.
 * @param issuer          giá trị claim "iss", parser từ chối token của hệ thống khác
 * @param accessTokenTtl  thời gian sống access token (mặc định 15 phút)
 * @param refreshTokenTtl thời gian sống refresh token (mặc định 7 ngày)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("ticketbox-api") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret phải dài ít nhất 32 byte (biến môi trường JWT_SECRET)");
        }
    }
}
