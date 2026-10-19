package com.ticketbox.auth.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Đăng ký {@link JwtProperties}. Để ở class riêng (không đặt @ConfigurationPropertiesScan trên lớp main)
 * để các test slice như @WebMvcTest không phải nạp và kiểm tra cấu hình JWT.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class AuthConfig {
}
