package com.ticketbox.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật @Scheduled (job trả ghế hết hạn; Giai đoạn 3: outbox poller, đối soát thanh toán). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
