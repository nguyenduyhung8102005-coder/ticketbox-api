package com.ticketbox.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Thay JavaMailSender thật bằng bản giữ email trong bộ nhớ (Spring Boot không tự tạo mail sender khi đã có bean). */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfiguration {

    @Bean
    public CapturingMailSender capturingMailSender() {
        return new CapturingMailSender();
    }
}
