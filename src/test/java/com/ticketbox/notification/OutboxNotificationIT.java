package com.ticketbox.notification;

import com.ticketbox.AbstractIntegrationTest;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.common.messaging.MessagingProperties;
import com.ticketbox.notification.messaging.NotificationService;
import com.ticketbox.notification.qr.QrCodeGeneratorTest;
import com.ticketbox.support.MailContent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Transactional Outbox → Kafka (container thật) → consumer notification → email có QR.
 * Kiểm tra: đúng nội dung, gửi trùng tin nhắn chỉ 1 email, SMTP lỗi thì thử lại, lỗi mãi thì vào DLT.
 */
class OutboxNotificationIT extends AbstractIntegrationTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;
    @Autowired
    private MessagingProperties messagingProperties;

    private ShowtimeFixture fixture;
    private long a1;
    private long a2;
    private String alice;

    @BeforeEach
    void setUp() {
        fixture = createOnSaleShowtime(1, 5);
        a1 = seatId(fixture.showtimeId(), "A", 1);
        a2 = seatId(fixture.showtimeId(), "A", 2);
        alice = customerToken("alice@test.vn");
    }

    @Test
    void confirmedBooking_isPublished_andCustomerReceivesEmailWithOneQrPerSeat() throws Exception {
        String code = payBooking(a1, a2);

        assertThat(outboxRelay.relayPending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_type = ?", String.class,
                EventTypes.BOOKING_CONFIRMED)).isEqualTo("PUBLISHED");

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                assertThat(mailSender.withSubjectContaining(code)).hasSize(1));
        MailContent mail = MailContent.of(mailSender.withSubjectContaining(code).get(0));

        assertThat(mail.recipients()).containsExactly("alice@test.vn");
        assertThat(mail.html()).contains("Phim Hot", code, "A1", "A2");
        assertThat(mail.inlineImages()).hasSize(2)
                .allSatisfy(img -> assertThat(img.contentType()).startsWith("image/png"));
        // Nội dung QR chính là mã vé đã ký lưu trong DB
        List<String> decoded = new ArrayList<>();
        for (MailContent.InlineImage image : mail.inlineImages()) {
            decoded.add(QrCodeGeneratorTest.decode(image.bytes()));
        }
        assertThat(decoded).containsExactlyInAnyOrderElementsOf(jdbc.queryForList("""
                SELECT i.ticket_code FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                 WHERE b.booking_code = ?""", String.class, code));
    }

    /** At-least-once: relay gửi lại cùng một sự kiện (vd chết trước khi kịp đánh dấu PUBLISHED). */
    @Test
    void sameEventDeliveredTwice_customerGetsOnlyOneEmail() {
        String code = payBooking(a1);
        outboxRelay.relayPending();
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                assertThat(mailSender.withSubjectContaining(code)).hasSize(1));

        jdbc.update("UPDATE outbox_events SET status = 'PENDING', next_attempt_at = now() WHERE event_type = ?",
                EventTypes.BOOKING_CONFIRMED);
        assertThat(outboxRelay.relayPending()).isEqualTo(1);   // tin nhắn trùng eventId lên Kafka lần 2

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mailSender.withSubjectContaining(code)).hasSize(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ?", Integer.class,
                NotificationService.CONSUMER)).isEqualTo(1);
    }

    @Test
    void mailServerFailsOnce_consumerRetriesWithBackoff_andEmailIsDelivered() {
        mailSender.failNext(1);
        String code = payBooking(a1);

        outboxRelay.relayPending();

        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                assertThat(mailSender.withSubjectContaining(code)).hasSize(1));
        assertThat(mailSender.attempts()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void mailServerKeepsFailing_messageEndsInDeadLetterTopic_andIsNotMarkedProcessed() {
        mailSender.failNext(1000);
        String code = payBooking(a1);
        long eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE event_type = ?", Long.class,
                EventTypes.BOOKING_CONFIRMED);

        outboxRelay.relayPending();

        List<String> deadLetters = readDeadLetters("\"eventId\":" + eventId + ",");
        assertThat(deadLetters).isNotEmpty();
        assertThat(deadLetters.get(0)).contains(code);
        assertThat(mailSender.withSubjectContaining(code)).isEmpty();
        // Không đánh dấu đã xử lý: đẩy lại từ DLT sau khi sửa SMTP sẽ gửi được email
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                eventId)).isZero();
    }

    @Test
    void malformedMessage_goesStraightToDeadLetterTopic_withoutBlockingOthers() throws Exception {
        String marker = "not-json-" + UUID.randomUUID();
        kafkaTemplate.send(messagingProperties.bookingEventsTopic(), "poison", marker).get();

        assertThat(readDeadLetters(marker)).isNotEmpty();

        // Tin nhắn hỏng không chặn partition: sự kiện bình thường sau đó vẫn được xử lý
        String code = payBooking(a1);
        outboxRelay.relayPending();
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                assertThat(mailSender.withSubjectContaining(code)).hasSize(1));
    }

    // ----- helpers -----

    /** Giữ ghế + thanh toán thành công qua IPN, trả về mã đơn. */
    private String payBooking(Long... seats) {
        String code = holdBooking(alice, fixture.showtimeId(), "alice-" + UUID.randomUUID().toString().substring(0, 8),
                seats);
        String txn = createPayment(alice, code);
        assertThat(sendIpn(signedVnPayParams(txn, 90000L * seats.length, "00", "00"))).isEqualTo("00");
        return code;
    }

    private List<String> readDeadLetters(String marker) {
        List<String> found = new ArrayList<>();
        try (Consumer<?, ?> consumer = consumerFactory.createConsumer("it-dlt-" + UUID.randomUUID(), "it")) {
            consumer.subscribe(List.of(messagingProperties.deadLetterTopic()));
            long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT.toMillis();
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(500))) {
                    String value = String.valueOf(record.value());
                    if (value.contains(marker)) {
                        found.add(value);
                    }
                }
            }
        }
        return found;
    }
}
