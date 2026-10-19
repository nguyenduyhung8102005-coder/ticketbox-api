package com.ticketbox.catalog.repository;

import com.ticketbox.catalog.domain.Show;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * Điều kiện lọc động cho danh sách show. Mỗi hàm trả về null khi tham số rỗng,
 * Spring Data bỏ qua Specification null nên ghép bằng {@code allOf(..)} rất gọn.
 */
public final class ShowSpecifications {

    private ShowSpecifications() {
    }

    public static Specification<Show> filter(ShowType type, ShowStatus status, String keyword) {
        return Specification.allOf(hasType(type), hasStatus(status), titleContains(keyword));
    }

    public static Specification<Show> hasType(ShowType type) {
        return type == null ? null : (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Show> hasStatus(ShowStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Show> titleContains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.<String>get("title")), pattern, '\\');
    }

    /** Escape ký tự đặc biệt của LIKE để người dùng gõ "50%" không thành wildcard. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
