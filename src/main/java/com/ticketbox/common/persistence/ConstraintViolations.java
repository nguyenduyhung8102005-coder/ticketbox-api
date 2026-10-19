package com.ticketbox.common.persistence;

import java.util.Locale;

/**
 * Nhận diện ràng buộc DB nào bị vi phạm dựa vào tên constraint trong thông điệp lỗi của PostgreSQL.
 * Đặt tên constraint rõ ràng trong migration (uk_..., ck_..., ex_...) chính là để làm được việc này.
 */
public final class ConstraintViolations {

    public static final String SHOWTIME_OVERLAP = "ex_showtimes_no_overlap";
    public static final String USER_EMAIL = "uk_users_email_lower";
    public static final String USER_PHONE = "uk_users_phone";
    public static final String SHOW_SLUG = "uk_shows_slug";
    public static final String BOOKING_IDEMPOTENCY_KEY = "uk_bookings_idempotency_key";

    private ConstraintViolations() {
    }

    /** Duyệt toàn bộ chuỗi cause, trả về true nếu có thông điệp nhắc tới constraint {@code name}. */
    public static boolean isViolationOf(Throwable ex, String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 20) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }
}
