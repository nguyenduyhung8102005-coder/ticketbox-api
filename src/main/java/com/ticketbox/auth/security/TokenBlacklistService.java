package com.ticketbox.auth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Thu hồi access token khi logout. JWT là stateless nên không "xóa" được, thay vào đó ghi jti
 * vào Redis với TTL đúng bằng thời gian sống còn lại: hết hạn thì token tự vô hiệu, key cũng tự mất,
 * blacklist không phình to theo thời gian.
 */
@Slf4j
@Component
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public TokenBlacklistService(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public void revoke(String tokenId, Instant expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        if (tokenId == null || remaining.isNegative() || remaining.isZero()) {
            return;   // token đã hết hạn, không cần chặn
        }
        redis.opsForValue().set(KEY_PREFIX + tokenId, "1", remaining);
    }

    /**
     * Fail-open khi Redis lỗi: ưu tiên hệ thống vẫn phục vụ được; cái giá là token vừa logout
     * có thể dùng tiếp tối đa 15 phút trong lúc Redis sự cố. Ghi log cảnh báo để theo dõi.
     */
    public boolean isRevoked(String tokenId) {
        if (tokenId == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + tokenId));
        } catch (DataAccessException e) {
            log.warn("Không kiểm tra được blacklist token (Redis lỗi): {}", e.getMessage());
            return false;
        }
    }
}
