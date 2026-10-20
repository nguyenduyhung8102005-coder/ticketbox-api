package com.ticketbox.payment.vnpay;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** VNPay dùng định dạng yyyyMMddHHmmss theo GMT+7 cho mọi trường thời gian. */
public final class VnPayDates {

    public static final ZoneOffset GMT7 = ZoneOffset.ofHours(7);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private VnPayDates() {
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant.atOffset(GMT7));
    }

    /** @return null nếu rỗng hoặc sai định dạng */
    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, FORMAT).toInstant(GMT7);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
