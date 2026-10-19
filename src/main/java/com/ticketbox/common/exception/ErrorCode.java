package com.ticketbox.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Danh mục mã lỗi của hệ thống. Tên enum chính là mã trả về cho client,
 * nên KHÔNG đổi tên một mã đã phát hành (client có thể đang phụ thuộc vào nó).
 */
public enum ErrorCode {

    // ===== Chung =====
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request không đúng định dạng"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập để thực hiện thao tác này"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức HTTP không được hỗ trợ"),
    DATA_CONFLICT(HttpStatus.CONFLICT, "Dữ liệu bị xung đột hoặc đã tồn tại"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Dữ liệu vừa được người khác cập nhật, vui lòng thử lại"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Bạn thao tác quá nhanh, vui lòng thử lại sau"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi xảy ra, vui lòng thử lại sau"),

    // ===== Catalog (Giai đoạn 1) =====
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy phim/sự kiện"),
    SHOWTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy suất chiếu"),
    SHOWTIME_OVERLAP(HttpStatus.CONFLICT, "Phòng chiếu đã có suất khác trùng thời gian"),

    // ===== Booking (Giai đoạn 2) =====
    SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "Ghế đã có người chọn, vui lòng chọn ghế khác"),
    SEAT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "Vượt quá số ghế tối đa cho một đơn"),
    SHOWTIME_NOT_ON_SALE(HttpStatus.CONFLICT, "Suất chiếu chưa mở bán hoặc đã ngừng bán"),
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy đơn đặt vé"),
    BOOKING_EXPIRED(HttpStatus.GONE, "Đơn đặt vé đã hết thời gian giữ chỗ"),

    // ===== Payment (Giai đoạn 3) =====
    PAYMENT_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "Chữ ký thanh toán không hợp lệ"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "Số tiền thanh toán không khớp với đơn hàng");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
