package com.ticketbox.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Bật JPA Auditing ở class riêng (không đặt trên lớp main) để các test slice
 * như @WebMvcTest không bị buộc phải khởi tạo JPA.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /**
     * Cắt về micro giây (độ chính xác của TIMESTAMPTZ trong PostgreSQL) để giá trị trong bộ nhớ trùng khớp với giá trị
     * đọc lại từ DB. Quan trọng với thanh toán: vnp_CreateDate được tính từ payments.created_at và phải giống hệt
     * khi gửi lại trong API truy vấn/hoàn tiền (PostgreSQL làm tròn nano giây, có thể nhảy sang giây kế tiếp).
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
