package com.ticketbox.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Refresh token dạng chuỗi ngẫu nhiên (opaque), lưu trong Redis để thu hồi được.
 * <ul>
 *   <li>Redis chỉ lưu SHA-256 của token: lộ dữ liệu Redis cũng không dùng được token.</li>
 *   <li>Xoay vòng (rotation): mỗi token chỉ dùng được một lần. {@code GETDEL} là thao tác nguyên tử,
 *       hai request dùng cùng một refresh token thì chỉ một request thành công.</li>
 * </ul>
 */
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String issue(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), String.valueOf(userId), properties.refreshTokenTtl());
        return token;
    }

    /** Dùng token một lần: trả về userId và xóa luôn token. */
    public Optional<Long> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().getAndDelete(key(token));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    /** Thu hồi khi logout; chỉ xóa nếu token thuộc đúng người dùng đang đăng xuất. */
    public void revoke(String token, Long userId) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = key(token);
        if (String.valueOf(userId).equals(redis.opsForValue().get(key))) {
            redis.delete(key);
        }
    }

    public long ttlSeconds() {
        return properties.refreshTokenTtl().toSeconds();
    }

    private static String key(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }
}
