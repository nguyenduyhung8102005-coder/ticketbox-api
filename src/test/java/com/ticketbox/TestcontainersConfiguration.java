package com.ticketbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Khởi động PostgreSQL, Redis và Kafka thật trong Docker cho integration test.
 * {@link ServiceConnection} tự động trỏ datasource/redis/kafka của Spring vào các container này,
 * không cần khai báo URL thủ công.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        // Dùng đúng phiên bản như docker-compose để tránh "chạy test thì qua, chạy thật thì lỗi"
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }

    /** Bản native (GraalVM) của Apache Kafka: cùng giao thức, khởi động nhanh hơn nhiều bản JVM. */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));
    }
}
