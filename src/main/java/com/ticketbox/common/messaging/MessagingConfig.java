package com.ticketbox.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka: khai báo topic và chiến lược xử lý lỗi chung cho mọi consumer.
 * <p>
 * Consumer ném lỗi → thử lại tại chỗ với backoff lũy thừa (1s, 2s, 4s, 8s) → vẫn lỗi thì chuyển tin nhắn sang
 * Dead Letter Topic {@code <topic>.DLT} và đi tiếp, không để một tin nhắn hỏng chặn cả partition.
 * Tin nhắn trong DLT giữ nguyên nội dung kèm header lý do lỗi, có thể xem lại và đẩy lại sau khi sửa.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

    @Bean
    public NewTopic bookingEventsTopic(MessagingProperties properties) {
        return TopicBuilder.name(properties.bookingEventsTopic())
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }

    @Bean
    public NewTopic bookingEventsDeadLetterTopic(MessagingProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(1)
                .replicas(properties.topicReplicas())
                .build();
    }

    /** Spring Boot tự gắn bean CommonErrorHandler duy nhất này vào container factory mặc định của @KafkaListener. */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                MessagingProperties properties) {
        // partition -1: để Kafka tự chọn partition của DLT (DLT chỉ có 1 partition, khác topic gốc)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(properties.deadLetterTopic(), -1));

        MessagingProperties.ConsumerRetry retry = properties.consumerRetry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Tin nhắn sai định dạng: thử lại vô ích, đưa thẳng vào DLT
        handler.addNotRetryableExceptions(NonRetryableEventException.class, JsonProcessingException.class);
        return handler;
    }
}
