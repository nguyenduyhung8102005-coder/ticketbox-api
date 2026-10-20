package com.ticketbox.notification.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param from     địa chỉ gửi
 * @param fromName tên hiển thị
 * @param timezone múi giờ hiển thị giờ chiếu trong email
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        @DefaultValue("no-reply@ticketbox.local") String from,
        @DefaultValue("Ticketbox") String fromName,
        @DefaultValue("Asia/Ho_Chi_Minh") String timezone) {
}
