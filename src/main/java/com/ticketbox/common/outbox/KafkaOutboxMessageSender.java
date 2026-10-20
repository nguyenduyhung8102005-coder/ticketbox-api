package com.ticketbox.common.outbox;

import com.ticketbox.common.messaging.MessagingProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaOutboxMessageSender implements OutboxMessageSender {

    public static final String EVENT_TYPE_HEADER = "event-type";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MessagingProperties properties;

    public KafkaOutboxMessageSender(KafkaTemplate<String, String> kafkaTemplate, MessagingProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void send(String key, String eventType, String message) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.bookingEventsTopic(), key, message);
        record.headers().add(EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        // Đồng bộ: chỉ đánh dấu PUBLISHED khi broker đã xác nhận (acks=all)
        kafkaTemplate.send(record).get(properties.outbox().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
