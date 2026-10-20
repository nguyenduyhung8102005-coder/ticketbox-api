package com.ticketbox.booking.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param signingSecret khóa HMAC ký mã vé, tối thiểu 32 ký tự. Đổi khóa thì mọi vé đã phát hành sẽ không còn
 *                      xác minh được, nên production phải cấu hình cố định qua biến TICKET_SIGNING_SECRET.
 */
@ConfigurationProperties(prefix = "app.ticket")
public record TicketProperties(String signingSecret) {
}
