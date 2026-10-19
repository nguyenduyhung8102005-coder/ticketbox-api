/**
 * Module Notification: email xác nhận, sinh vé QR, tiêu thụ sự kiện từ outbox (Giai đoạn 3).
 * <p>
 * Quy tắc ranh giới: module khác chỉ được gọi qua service interface công khai
 * hoặc domain event, không truy cập trực tiếp repository/entity của module này.
 */
package com.ticketbox.notification;
