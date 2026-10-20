package com.ticketbox.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbox.common.events.PaymentRefundRequestedEvent;
import com.ticketbox.common.messaging.EventEnvelope;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.common.outbox.KafkaOutboxMessageSender;
import com.ticketbox.payment.service.RefundService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Nhận PAYMENT_REFUND_REQUESTED và gọi API hoàn tiền. Lỗi thì ném exception: error handler thử lại với backoff,
 * hết lượt thì đưa vào DLT; job đối soát vẫn quét REFUND_PENDING làm lưới an toàn.
 * Idempotent nhờ kiểm tra trạng thái trong {@link RefundService} (không cần processed_events).
 */
@Slf4j
@Component
public class RefundRequestListener {

    static final String CONSUMER = "payment-refund";

    private final ObjectMapper objectMapper;
    private final RefundService refundService;

    public RefundRequestListener(ObjectMapper objectMapper, RefundService refundService) {
        this.objectMapper = objectMapper;
        this.refundService = refundService;
    }

    @KafkaListener(id = CONSUMER, groupId = CONSUMER,
            topics = "${app.messaging.booking-events-topic:ticketbox.booking-events}")
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        Header header = record.headers().lastHeader(KafkaOutboxMessageSender.EVENT_TYPE_HEADER);
        if (header != null && !EventTypes.PAYMENT_REFUND_REQUESTED.equals(
                new String(header.value(), StandardCharsets.UTF_8))) {
            return;   // lọc nhanh bằng header, không cần parse JSON
        }
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        if (!EventTypes.PAYMENT_REFUND_REQUESTED.equals(envelope.eventType())) {
            return;
        }
        PaymentRefundRequestedEvent event =
                objectMapper.treeToValue(envelope.payload(), PaymentRefundRequestedEvent.class);
        boolean refunded = refundService.refund(event.txnRef());
        log.info("Sự kiện {}: hoàn tiền giao dịch {} → {}", envelope.eventId(), event.txnRef(),
                refunded ? "đã gửi yêu cầu" : "không cần (đã xử lý)");
    }
}
