package com.ticketbox.common.exception;

/**
 * Lỗi nghiệp vụ có chủ đích (ghế đã bị chọn, suất chiếu không tồn tại...).
 * Ném từ tầng service, được {@link GlobalExceptionHandler} chuyển thành response chuẩn.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
