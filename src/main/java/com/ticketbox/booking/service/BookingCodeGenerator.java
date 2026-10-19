package com.ticketbox.booking.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Sinh mã đơn dạng "TB" + 10 ký tự, ví dụ TB7KQ2M9XA4F.
 * Bảng chữ cái Crockford Base32 bỏ I, L, O, U để khách đọc qua điện thoại không nhầm 0/O, 1/I.
 * 32^10 ≈ 10^15 tổ hợp, không đoán được mã đơn của người khác; unique constraint là chốt chặn cuối.
 */
@Component
public class BookingCodeGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 10;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder("TB");
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
