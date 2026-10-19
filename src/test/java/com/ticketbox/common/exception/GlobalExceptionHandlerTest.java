package com.ticketbox.common.exception;

import com.ticketbox.auth.security.JwtService;
import com.ticketbox.auth.security.TokenBlacklistService;
import com.ticketbox.common.config.SecurityConfig;
import com.ticketbox.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm tra định dạng lỗi thống nhất. Chỉ khởi động tầng Web (không DB, không Docker) nên chạy rất nhanh.
 * Từ Giai đoạn 1, /api/** yêu cầu đăng nhập nên các test mặc định chạy với một user giả (@WithMockUser).
 */
@WithMockUser
@WebMvcTest(controllers = GlobalExceptionHandlerTest.FakeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandlerTest.FakeController.class})   // @WebMvcTest không tự nạp @Configuration, cần import để test cả lớp bảo mật
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    // SecurityConfig cần 2 bean này để dựng JwtAuthenticationFilter; test không gửi Bearer token nên chỉ cần mock
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    @Test
    void businessException_returnsMappedStatusAndCode() throws Exception {
        mockMvc.perform(get("/api/test/seat-taken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SEAT_ALREADY_TAKEN"))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.SEAT_ALREADY_TAKEN.defaultMessage()))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void invalidBody_returnsFieldViolations() throws Exception {
        mockMvc.perform(post("/api/test/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.violations[0].field").value("title"));
    }

    @Test
    void invalidRequestParam_returnsValidationError() throws Exception {
        mockMvc.perform(get("/api/test/page").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.violations[0].field").value("page"));
    }

    @Test
    void malformedJson_returnsMalformedRequest() throws Exception {
        mockMvc.perform(post("/api/test/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unexpectedException_hidesInternalDetails() throws Exception {
        mockMvc.perform(get("/api/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret-db-password"))));
    }

    @Test
    void validIncomingRequestId_isPropagated() throws Exception {
        String requestId = "client-trace-12345";
        mockMvc.perform(get("/api/test/seat-taken").header(RequestIdFilter.HEADER, requestId))
                .andExpect(header().string(RequestIdFilter.HEADER, requestId))
                .andExpect(jsonPath("$.error.requestId").value(requestId));
    }

    @Test
    void unsafeIncomingRequestId_isReplaced() throws Exception {
        mockMvc.perform(get("/api/test/seat-taken").header(RequestIdFilter.HEADER, "bad\nid"))
                .andExpect(header().string(RequestIdFilter.HEADER, not("bad\nid")));
    }

    @Test
    @WithAnonymousUser
    void adminArea_withoutLogin_returnsJson401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/shows"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void adminArea_asCustomer_returnsJson403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/shows"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @WithAnonymousUser
    void protectedApi_withoutLogin_returnsJson401() throws Exception {
        mockMvc.perform(get("/api/test/seat-taken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void dataIntegrityViolation_onKnownConstraint_isMappedToBusinessCode() throws Exception {
        mockMvc.perform(get("/api/test/overlap"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHOWTIME_OVERLAP"));
    }

    // ----- Controller giả lập chỉ dùng trong test -----

    record CreateShowRequest(@NotBlank String title) {
    }

    @RestController
    @RequestMapping("/api/test")
    static class FakeController {

        @GetMapping("/seat-taken")
        void seatTaken() {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN);
        }

        @PostMapping("/shows")
        void create(@Valid @RequestBody CreateShowRequest request) {
        }

        @GetMapping("/page")
        void page(@RequestParam @Min(0) int page) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret-db-password leaked");
        }

        @GetMapping("/overlap")
        void overlap() {
            throw new org.springframework.dao.DataIntegrityViolationException("insert failed",
                    new RuntimeException("ERROR: conflicting key value violates exclusion constraint "
                            + "\"ex_showtimes_no_overlap\""));
        }
    }
}
