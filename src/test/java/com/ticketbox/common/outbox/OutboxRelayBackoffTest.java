package com.ticketbox.common.outbox;

import com.ticketbox.common.messaging.MessagingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxRelayBackoffTest {

    private final MessagingProperties properties = new MessagingProperties("topic", 3, 1,
            new MessagingProperties.Outbox(true, Duration.ofSeconds(1), 100, 10, Duration.ofSeconds(2),
                    Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofDays(7)),
            new MessagingProperties.ConsumerRetry(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)));

    private final OutboxRelay relay = new OutboxRelay(null, null, null, properties, null, Clock.systemUTC(),
            mock(PlatformTransactionManager.class));

    @Test
    void backoffDoublesEachAttempt_upToMax() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(relay.backoff(5)).isEqualTo(Duration.ofSeconds(32));
        assertThat(relay.backoff(10)).isEqualTo(Duration.ofMinutes(10));
        assertThat(relay.backoff(60)).isEqualTo(Duration.ofMinutes(10));   // không tràn số
    }
}
