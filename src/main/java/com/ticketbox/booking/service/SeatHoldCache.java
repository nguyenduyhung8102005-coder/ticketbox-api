package com.ticketbox.booking.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * LỚP BẢO VỆ THỨ NHẤT: giữ ghế trong Redis bằng Lua script.
 * <p>
 * Redis chạy script nguyên tử (không lệnh nào chen vào giữa), nên "kiểm tra tất cả ghế còn trống rồi
 * giữ tất cả" là một bước duy nhất: all-or-nothing. Request tranh chấp bị từ chối trong vài trăm micro giây
 * mà không mở transaction, không giữ connection DB, không khóa dòng nào trong PostgreSQL.
 * <p>
 * Redis KHÔNG phải nguồn sự thật (có thể mất dữ liệu khi restart); PostgreSQL mới là. Vì vậy:
 * Redis từ chối thì chắc chắn từ chối; Redis đồng ý thì vẫn phải qua UPDATE có điều kiện ở DB.
 */
@Slf4j
@Component
public class SeatHoldCache {

    /** KEYS = các ghế, ARGV[1] = token của người giữ (mã đơn), ARGV[2] = TTL mili giây. */
    private static final RedisScript<Long> HOLD_ALL_SCRIPT = new DefaultRedisScript<>("""
            for i, key in ipairs(KEYS) do
                if redis.call('EXISTS', key) == 1 then
                    return 0
                end
            end
            for i, key in ipairs(KEYS) do
                redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    /**
     * Chỉ xóa key nếu vẫn đang do CHÍNH MÌNH giữ (so sánh token). Nếu key của mình đã hết hạn và
     * người khác vừa giữ ghế đó, DEL thẳng tay sẽ xóa nhầm chỗ của họ.
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local released = 0
            for i, key in ipairs(KEYS) do
                if redis.call('GET', key) == ARGV[1] then
                    redis.call('DEL', key)
                    released = released + 1
                end
            end
            return released
            """, Long.class);

    private final StringRedisTemplate redis;

    public SeatHoldCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public enum HoldResult {
        /** Đã giữ được tất cả ghế trong Redis. */
        ACQUIRED,
        /** Có ghế đang bị người khác giữ: từ chối ngay. */
        REJECTED,
        /** Redis lỗi/không kết nối được: bỏ qua lớp này, để PostgreSQL quyết định (giảm hiệu năng, không sai). */
        UNAVAILABLE
    }

    public HoldResult tryHoldAll(Long showtimeId, Collection<Long> showtimeSeatIds, String token, Duration ttl) {
        try {
            Long result = redis.execute(HOLD_ALL_SCRIPT, keys(showtimeId, showtimeSeatIds),
                    token, String.valueOf(ttl.toMillis()));
            return result != null && result == 1L ? HoldResult.ACQUIRED : HoldResult.REJECTED;
        } catch (DataAccessException e) {
            log.warn("Redis không khả dụng khi giữ ghế, chuyển sang chỉ dùng PostgreSQL: {}", e.getMessage());
            return HoldResult.UNAVAILABLE;
        }
    }

    public void releaseAll(Long showtimeId, Collection<Long> showtimeSeatIds, String token) {
        if (showtimeSeatIds.isEmpty()) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, keys(showtimeId, showtimeSeatIds), token);
        } catch (DataAccessException e) {
            // Không sao: key sẽ tự hết hạn theo TTL
            log.warn("Không nhả được ghế trong Redis (sẽ tự hết hạn theo TTL): {}", e.getMessage());
        }
    }

    /**
     * Hash tag {showtimeId}: mọi ghế của một suất nằm cùng một slot khi chạy Redis Cluster,
     * điều kiện bắt buộc để một Lua script thao tác được nhiều key.
     */
    public static String key(Long showtimeId, Long showtimeSeatId) {
        return "seat:hold:{" + showtimeId + "}:" + showtimeSeatId;
    }

    private static List<String> keys(Long showtimeId, Collection<Long> showtimeSeatIds) {
        return showtimeSeatIds.stream().map(id -> key(showtimeId, id)).toList();
    }
}
