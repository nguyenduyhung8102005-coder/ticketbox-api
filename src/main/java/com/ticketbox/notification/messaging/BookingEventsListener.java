package com.ticketbox.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbox.common.messaging.EventEnvelope;
import com.ticketbox.common.messaging.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "notification": nhận mọi sự kiện trên topic, chỉ xử lý loại liên quan tới email.
 * Exception ném ra được {@code DefaultErrorHandler} (MessagingConfig) thử lại với backoff rồi đưa vào DLT.
 */
@Component
public class BookingEventsListener {

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    public BookingEventsListener(ObjectMapper objectMapper, NotificationService notificationService) {
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @KafkaListener(id = NotificationService.CONSUMER, groupId = NotificationService.CONSUMER,
            topics = "${app.messaging.booking-events-topic:ticketbox.booking-events}")
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new NonRetryableEventException("Tin nhắn không đọc được tại offset " + record.offset(), e);
        }
        if (envelope.eventType() == null || envelope.payload() == null) {
            throw new NonRetryableEventException("Tin nhắn thiếu eventType/payload tại offset " + record.offset(),
                    null);
        }
        notificationService.handle(envelope);
    }
}
