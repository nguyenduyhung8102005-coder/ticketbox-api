package com.ticketbox.common.outbox;

/** Đích của outbox (Kafka). Tách interface để test relay không cần broker thật. */
public interface OutboxMessageSender {

    /**
     * Gửi và CHỜ broker xác nhận. Ném exception nếu không chắc tin nhắn đã được ghi nhận.
     *
     * @param key       khóa phân vùng: tin nhắn cùng key vào cùng partition, giữ đúng thứ tự
     * @param eventType ghi vào header để consumer lọc nhanh
     */
    void send(String key, String eventType, String message) throws Exception;
}
