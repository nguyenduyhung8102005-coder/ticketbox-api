package com.ticketbox.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chạy {@link OutboxRelay} mỗi giây và dọn outbox mỗi đêm.
 * Tắt bằng app.messaging.outbox.relay-enabled=false (profile test tắt để test tự gọi relay, chủ động thời điểm).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.messaging.outbox", name = "relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.messaging.outbox.relay-interval:PT1S}",
            initialDelayString = "${app.messaging.outbox.relay-interval:PT1S}")
    public void relay() {
        try {
            relay.relayPending();
        } catch (RuntimeException e) {
            log.error("Outbox relay lỗi, lượt sau thử lại", e);
        }
    }

    /** 3 giờ sáng giờ Việt Nam. */
    @Scheduled(cron = "0 0 3 * * *", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void cleanUp() {
        try {
            relay.cleanUp();
        } catch (RuntimeException e) {
            log.error("Dọn outbox lỗi", e);
        }
    }
}
