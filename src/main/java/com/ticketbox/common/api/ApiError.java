package com.ticketbox.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Chi tiết lỗi trả về cho client.
 *
 * @param code       mã lỗi nghiệp vụ ổn định (client dựa vào đây để xử lý, không dựa vào message)
 * @param message    thông điệp dễ đọc cho người dùng
 * @param requestId  mã truy vết, khớp với log phía server
 * @param violations danh sách lỗi theo từng trường (chỉ có khi lỗi validate)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, String requestId, List<FieldViolation> violations) {

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId, List.of());
    }

    public record FieldViolation(String field, String message) {
    }
}
