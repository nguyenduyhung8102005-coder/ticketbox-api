package com.ticketbox.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình Swagger UI tại /swagger-ui.html.
 * Khai báo sẵn scheme "bearerAuth" để Giai đoạn 1 chỉ cần gắn
 * {@code @SecurityRequirement(name = "bearerAuth")} lên các API cần đăng nhập.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI ticketboxOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ticketbox API")
                        .version("v1")
                        .description("API hệ thống đặt vé xem phim và sự kiện trực tuyến"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
