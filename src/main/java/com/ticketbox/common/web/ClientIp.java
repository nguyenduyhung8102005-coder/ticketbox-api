package com.ticketbox.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP của người dùng cuối. Sau reverse proxy (Nginx, load balancer) thì IP thật nằm ở X-Forwarded-For.
 * Chỉ tin header này khi ứng dụng thật sự đứng sau proxy do mình quản lý (proxy phải ghi đè header từ client).
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "127.0.0.1" : remote;
    }
}
