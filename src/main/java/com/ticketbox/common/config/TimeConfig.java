package com.ticketbox.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Inject {@link Clock} thay vì gọi Instant.now() trực tiếp trong nghiệp vụ.
 * Nhờ vậy test được các logic phụ thuộc thời gian (ghế hết hạn giữ chỗ, mở bán flash sale...)
 * bằng cách truyền Clock.fixed(...) trong test.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
