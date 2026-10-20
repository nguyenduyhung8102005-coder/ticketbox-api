package com.ticketbox.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbox.common.events.BookingConfirmedEvent;
import com.ticketbox.common.events.PaymentRefundRequestedEvent;
import com.ticketbox.common.events.PaymentRefundedEvent;
import com.ticketbox.common.messaging.EventEnvelope;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.common.messaging.NonRetryableEventException;
import com.ticketbox.common.messaging.ProcessedEventStore;
import com.ticketbox.notification.email.TicketMailer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Xử lý sự kiện → gửi email, idempotent.
 *
 * <pre>
 * BEGIN
 *   INSERT processed_events(eventId, 'notification') ON CONFLICT DO NOTHING
 *     └─ 0 dòng → đã gửi rồi, bỏ qua (Kafka giao lại, outbox gửi lại...)
 *   gửi email  ── lỗi → ROLLBACK (xóa đánh dấu) → error handler thử lại với backoff → hết lượt: DLT
 * COMMIT
 * </pre>
 * Vẫn còn một khe nhỏ: email đã gửi nhưng COMMIT lỗi → lần sau gửi lại (trùng email). Đây là giới hạn của
 * mọi hệ thống gửi email (SMTP không tham gia transaction); gửi trùng tốt hơn là không gửi.
 */
@Slf4j
@Service
public class NotificationService {

    public static final String CONSUMER = "notification";

    private final ProcessedEventStore processedEventStore;
    private final TicketMailer ticketMailer;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public NotificationService(ProcessedEventStore processedEventStore, TicketMailer ticketMailer,
                               ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.processedEventStore = processedEventStore;
        this.ticketMailer = ticketMailer;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** @return true nếu đã gửi email trong lần gọi này */
    public boolean handle(EventEnvelope envelope) {
        Runnable action = switch (envelope.eventType()) {
            case EventTypes.BOOKING_CONFIRMED -> {
                BookingConfirmedEvent event = payload(envelope, BookingConfirmedEvent.class);
                yield () -> ticketMailer.sendBookingConfirmed(event);
            }
            case EventTypes.PAYMENT_REFUND_REQUESTED -> {
                PaymentRefundRequestedEvent event = payload(envelope, PaymentRefundRequestedEvent.class);
                yield () -> ticketMailer.sendRefundRequested(event);
            }
            case EventTypes.PAYMENT_REFUNDED -> {
                PaymentRefundedEvent event = payload(envelope, PaymentRefundedEvent.class);
                yield () -> ticketMailer.sendRefunded(event);
            }
            default -> null;   // sự kiện module này không quan tâm
        };
        if (action == null) {
            return false;
        }
        Boolean sent = tx.execute(status -> {
            if (!processedEventStore.markProcessed(envelope.eventId(), CONSUMER)) {
                log.info("Sự kiện {} ({}) đã xử lý trước đó, bỏ qua", envelope.eventId(), envelope.eventType());
                return false;
            }
            action.run();
            return true;
        });
        return Boolean.TRUE.equals(sent);
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new NonRetryableEventException("Payload sự kiện " + envelope.eventId() + " sai định dạng", e);
        }
    }
}
