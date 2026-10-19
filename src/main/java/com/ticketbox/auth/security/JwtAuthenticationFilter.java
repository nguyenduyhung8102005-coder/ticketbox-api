package com.ticketbox.auth.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Đọc header {@code Authorization: Bearer <token>}, xác minh và đặt người dùng vào SecurityContext.
 * Token sai/hết hạn/đã logout thì KHÔNG chặn ở đây mà để request đi tiếp dưới dạng ẩn danh:
 * API public vẫn chạy, API cần đăng nhập sẽ bị Spring Security trả 401.
 * <p>
 * Không đánh dấu @Component: nếu là bean, Spring Boot tự đăng ký thêm vào servlet filter chain
 * và filter sẽ chạy hai lần. Lớp này được tạo trong SecurityConfig.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenBlacklistService blacklist;

    public JwtAuthenticationFilter(JwtService jwtService, TokenBlacklistService blacklist) {
        this.jwtService = jwtService;
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(header.substring(BEARER_PREFIX.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            AuthUser user = jwtService.parseAccessToken(token);
            if (blacklist.isRevoked(user.tokenId())) {
                log.debug("Access token {} đã bị thu hồi", user.tokenId());
                return;
            }
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
            var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Access token không hợp lệ: {}", e.getMessage());
        }
    }
}
