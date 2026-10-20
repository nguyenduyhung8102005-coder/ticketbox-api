package com.ticketbox.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cấu hình sự kiện (prefix app.messaging).
 *
 * @param bookingEventsTopic topic Kafka chứa mọi sự kiện nghiệp vụ của đơn/thanh toán
 * @param topicPartitions    số partition; key = aggregate nên sự kiện của cùng một đơn luôn vào cùng partition
 * @param topicReplicas      hệ số nhân bản (1 cho local, 3 cho production)
 * @param outbox             cấu hình tiến trình đẩy outbox lên Kafka
 * @param consumerRetry      cấu hình thử lại của consumer trước khi đưa tin nhắn vào Dead Letter Topic
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        @DefaultValue("ticketbox.booking-events") String bookingEventsTopic,
        @DefaultValue("3") int topicPartitions,
        @DefaultValue("1") int topicReplicas,
        @DefaultValue Outbox outbox,
        @DefaultValue ConsumerRetry consumerRetry) {

    /** Tin nhắn xử lý lỗi liên tục được chuyển sang topic này để không chặn các tin nhắn phía sau. */
    public String deadLetterTopic() {
        return bookingEventsTopic + ".DLT";
    }

    /**
     * @param relayEnabled   bật job đẩy outbox (profile test tắt để test tự gọi)
     * @param relayInterval  chu kỳ quét bảng outbox
     * @param batchSize      số sự kiện mỗi lô
     * @param maxAttempts    quá số lần gửi lỗi thì chuyển FAILED, chờ xử lý tay
     * @param initialBackoff thời gian chờ sau lần lỗi đầu, nhân đôi sau mỗi lần lỗi
     * @param maxBackoff     trần thời gian chờ
     * @param sendTimeout    thời gian chờ Kafka xác nhận mỗi tin nhắn
     * @param retention      giữ sự kiện đã gửi bao lâu trước khi dọn
     */
    public record Outbox(
            @DefaultValue("true") boolean relayEnabled,
            @DefaultValue("PT1S") Duration relayInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("PT2S") Duration initialBackoff,
            @DefaultValue("PT10M") Duration maxBackoff,
            @DefaultValue("PT5S") Duration sendTimeout,
            @DefaultValue("P7D") Duration retention) {
    }

    /**
     * Backoff lũy thừa: 1s, 2s, 4s, 8s... tối đa maxInterval, thử lại maxRetries lần rồi đưa vào DLT.
     */
    public record ConsumerRetry(
            @DefaultValue("4") int maxRetries,
            @DefaultValue("PT1S") Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("PT30S") Duration maxInterval) {
    }
}
