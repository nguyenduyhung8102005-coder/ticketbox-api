package com.ticketbox.common.messaging;

/**
 * Tin nhắn hỏng (sai định dạng, thiếu trường bắt buộc): thử lại bao nhiêu lần cũng lỗi,
 * nên error handler đưa thẳng vào Dead Letter Topic, không tốn các lượt retry.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
