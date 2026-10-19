package com.ticketbox;

import org.springframework.boot.SpringApplication;

/**
 * Chạy ứng dụng local với PostgreSQL/Redis do Testcontainers tự dựng,
 * không cần docker compose:  mvn spring-boot:test-run
 * (hoặc chạy hàm main này trong IDE).
 */
public class TestTicketboxApplication {

    public static void main(String[] args) {
        SpringApplication.from(TicketboxApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
