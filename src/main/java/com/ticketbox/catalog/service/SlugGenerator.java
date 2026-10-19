package com.ticketbox.catalog.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Sinh slug thân thiện URL từ tiêu đề tiếng Việt: "Lật Mặt 8: Vòng Tay Nắng" -> "lat-mat-8-vong-tay-nang".
 */
public final class SlugGenerator {

    private static final int MAX_LENGTH = 200;

    private SlugGenerator() {
    }

    public static String slugify(String input) {
        if (input == null) {
            return "show";
        }
        // "đ" không phải chữ có dấu tổ hợp nên Normalizer không tách được, phải thay tay
        String s = input.replace('đ', 'd').replace('Đ', 'D');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "show" : s;
    }

    /** Thêm hậu tố -2, -3... cho tới khi không trùng. */
    public static String unique(String base, Predicate<String> exists) {
        if (!exists.test(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis();
    }
}
