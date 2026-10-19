package com.ticketbox.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketbox.AbstractIntegrationTest;
import com.ticketbox.user.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Luồng xác thực đầy đủ qua HTTP với PostgreSQL + Redis thật. */
class AuthIT extends AbstractIntegrationTest {

    @Test
    void register_thenAccessProtectedApi_thenLogout_tokenIsRevoked() {
        ResponseEntity<JsonNode> registered = call(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("email", "An@Gmail.com", "password", PASSWORD, "fullName", "Nguyễn An"), null);

        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        String accessToken = registered.getBody().at("/data/accessToken").asText();
        String refreshToken = registered.getBody().at("/data/refreshToken").asText();
        assertThat(registered.getBody().at("/data/user/email").asText()).isEqualTo("an@gmail.com");
        assertThat(registered.getBody().at("/data/user/role").asText()).isEqualTo("CUSTOMER");

        ResponseEntity<JsonNode> me = call(HttpMethod.GET, "/api/v1/me", null, accessToken);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().at("/data/fullName").asText()).isEqualTo("Nguyễn An");

        ResponseEntity<JsonNode> logout = call(HttpMethod.POST, "/api/v1/auth/logout",
                Map.of("refreshToken", refreshToken), accessToken);
        assertThat(logout.getStatusCode().value()).isEqualTo(200);

        // Access token chưa hết hạn nhưng đã nằm trong blacklist Redis
        ResponseEntity<JsonNode> afterLogout = call(HttpMethod.GET, "/api/v1/me", null, accessToken);
        assertThat(afterLogout.getStatusCode().value()).isEqualTo(401);
        assertThat(afterLogout.getBody().at("/error/code").asText()).isEqualTo("UNAUTHORIZED");

        // Refresh token cũng đã bị thu hồi
        ResponseEntity<JsonNode> refresh = call(HttpMethod.POST, "/api/v1/auth/refresh",
                Map.of("refreshToken", refreshToken), null);
        assertThat(refresh.getBody().at("/error/code").asText()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void register_duplicateEmailIgnoringCase_returns409() {
        createUser("an@gmail.com", Role.CUSTOMER);

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("email", "AN@gmail.com", "password", PASSWORD, "fullName", "An"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void register_invalidPayload_returnsFieldViolations() {
        Map<String, Object> body = new HashMap<>();
        body.put("email", "khong-phai-email");
        body.put("password", "123");
        body.put("fullName", "");

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/auth/register", body, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().at("/error/violations").size()).isEqualTo(3);
    }

    @Test
    void login_wrongPassword_returns401() {
        createUser("an@gmail.com", Role.CUSTOMER);

        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/auth/login",
                Map.of("email", "an@gmail.com", "password", "sai-mat-khau"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void refreshToken_canBeUsedOnlyOnce() {
        createUser("an@gmail.com", Role.CUSTOMER);
        ResponseEntity<JsonNode> login = call(HttpMethod.POST, "/api/v1/auth/login",
                Map.of("email", "an@gmail.com", "password", PASSWORD), null);
        String refreshToken = login.getBody().at("/data/refreshToken").asText();

        ResponseEntity<JsonNode> first = call(HttpMethod.POST, "/api/v1/auth/refresh",
                Map.of("refreshToken", refreshToken), null);
        ResponseEntity<JsonNode> reused = call(HttpMethod.POST, "/api/v1/auth/refresh",
                Map.of("refreshToken", refreshToken), null);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().at("/data/refreshToken").asText()).isNotEqualTo(refreshToken);
        assertThat(reused.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void invalidBearerToken_onProtectedApi_returns401() {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/api/v1/me", null, "not.a.jwt");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void customer_cannotAccessAdminApi() {
        String token = customerToken("khach@test.vn");

        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/api/v1/admin/shows", null, token);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void admin_canAccessAdminApi() {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/api/v1/admin/shows", null, adminToken());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
