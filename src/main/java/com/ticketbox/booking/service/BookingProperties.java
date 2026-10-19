package com.ticketbox.booking.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cấu hình đặt vé (prefix app.booking).
 *
 * @param holdTtl            thời gian giữ ghế chờ thanh toán, dùng chung cho TTL Redis và held_until trong DB
 * @param maxSeatsPerBooking số ghế tối đa trong một đơn
 * @param expiryJob          cấu hình job trả ghế hết hạn
 */
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(
        @DefaultValue("10m") Duration holdTtl,
        @DefaultValue("8") int maxSeatsPerBooking,
        @DefaultValue ExpiryJob expiryJob) {

    public record ExpiryJob(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT30S") Duration interval,
            @DefaultValue("100") int batchSize) {
    }
}
