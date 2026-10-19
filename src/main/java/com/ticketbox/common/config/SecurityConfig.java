package com.ticketbox.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketbox.auth.security.JwtAuthenticationFilter;
import com.ticketbox.auth.security.JwtService;
import com.ticketbox.auth.security.TokenBlacklistService;
import com.ticketbox.common.api.ApiError;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * Cấu hình bảo mật.
 * <ul>
 *   <li>Stateless + JWT: mỗi request tự mang access token, server không giữ session.</li>
 *   <li>Phân quyền 2 lớp: theo URL ở đây (/api/v1/admin/** chỉ ADMIN) và @PreAuthorize trên controller.</li>
 *   <li>Lỗi 401/403 trả JSON cùng định dạng {@link ApiResponse} thay vì trang HTML mặc định.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    };

    private static final String[] PUBLIC_CATALOG_ENDPOINTS = {
            "/api/v1/shows",
            "/api/v1/shows/**",
            "/api/v1/showtimes/*/seats"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ObjectMapper objectMapper,
                                                   JwtService jwtService,
                                                   TokenBlacklistService tokenBlacklistService) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)          // API stateless dùng token, không dùng cookie session
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, tokenBlacklistService),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_CATALOG_ENDPOINTS).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeError(res, objectMapper, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) ->
                                writeError(res, objectMapper, ErrorCode.FORBIDDEN)));

        return http.build();
    }

    /** BCrypt strength mặc định 10: khoảng 50–100 ms mỗi lần băm, đủ chậm để chống brute-force. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", RequestIdFilter.HEADER));
        config.setExposedHeaders(List.of(RequestIdFilter.HEADER));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code)
            throws IOException {
        ApiError error = ApiError.of(code.name(), code.defaultMessage(), MDC.get(RequestIdFilter.MDC_KEY));
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(error));
    }
}
