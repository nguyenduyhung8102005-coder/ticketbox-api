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

    // ===== Auth / User (Giai đoạn 1) =====
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email đã được sử dụng"),
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại"),

    // ===== Catalog (Giai đoạn 1) =====
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy phim/sự kiện"),
    SHOWTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy suất chiếu"),
    SHOWTIME_OVERLAP(HttpStatus.CONFLICT, "Phòng chiếu đã có suất khác trùng thời gian"),
    SLUG_ALREADY_EXISTS(HttpStatus.CONFLICT, "Đường dẫn (slug) đã được sử dụng"),
    SHOW_HAS_SHOWTIMES(HttpStatus.CONFLICT, "Phim/sự kiện đã có suất chiếu, không thể xóa"),
    SHOW_NOT_SCHEDULABLE(HttpStatus.CONFLICT, "Phim/sự kiện đã kết thúc, không thể tạo suất chiếu"),
    VENUE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy địa điểm"),
    AUDITORIUM_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy phòng chiếu"),
    SEAT_LAYOUT_ALREADY_EXISTS(HttpStatus.CONFLICT, "Phòng chiếu đã có sơ đồ ghế"),
    SEAT_LAYOUT_EMPTY(HttpStatus.CONFLICT, "Phòng chiếu chưa có sơ đồ ghế"),
    TICKET_PRICE_MISSING(HttpStatus.BAD_REQUEST, "Thiếu giá vé cho loại ghế trong phòng chiếu"),
    SHOWTIME_STATUS_TRANSITION_INVALID(HttpStatus.CONFLICT, "Không thể chuyển suất chiếu sang trạng thái này"),
    SHOWTIME_HAS_BOOKINGS(HttpStatus.CONFLICT, "Suất chiếu đã có ghế được giữ hoặc đã bán"),

    // ===== Booking (Giai đoạn 2) =====
    SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "Ghế đã có người chọn, vui lòng chọn ghế khác"),
    SEAT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "Vượt quá số ghế tối đa cho một đơn"),
    SEAT_NOT_FOUND(HttpStatus.BAD_REQUEST, "Có ghế không thuộc suất chiếu này"),
    SHOWTIME_NOT_ON_SALE(HttpStatus.CONFLICT, "Suất chiếu chưa mở bán hoặc đã ngừng bán"),
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy đơn đặt vé"),
    BOOKING_EXPIRED(HttpStatus.GONE, "Đơn đặt vé đã hết thời gian giữ chỗ"),
    BOOKING_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Đơn đặt vé không còn ở trạng thái có thể hủy"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY,
            "Idempotency-Key đã được dùng cho một yêu cầu khác"),

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
