package com.ticketbox.booking.web;

import com.ticketbox.auth.security.AuthUser;
import com.ticketbox.booking.dto.BookingDtos.BookingResponse;
import com.ticketbox.booking.dto.BookingDtos.HoldSeatsCommand;
import com.ticketbox.booking.dto.BookingDtos.HoldSeatsRequest;
import com.ticketbox.booking.dto.BookingDtos.SeatMapResponse;
import com.ticketbox.booking.repository.BookingSummaryView;
import com.ticketbox.booking.service.BookingService;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.api.PageResponse;
import com.ticketbox.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bookings", description = "Sơ đồ ghế, giữ ghế, xem và hủy đơn")
@RestController
@RequestMapping("/api/v1")
public class BookingController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "Sơ đồ ghế của suất chiếu kèm trạng thái và giá (public)")
    @GetMapping("/showtimes/{id}/seats")
    public ApiResponse<SeatMapResponse> seatMap(@PathVariable Long id) {
        return ApiResponse.ok(bookingService.seatMap(id));
    }

    @Operation(summary = "Giữ ghế và tạo đơn chờ thanh toán (giữ trong 10 phút)",
            description = """
                    Gửi header Idempotency-Key (ví dụ một UUID sinh ở client cho mỗi lần bấm "Đặt vé").
                    Gửi lại cùng key và cùng danh sách ghế sẽ nhận lại đúng đơn cũ thay vì tạo đơn mới.
                    409 SEAT_ALREADY_TAKEN nếu có ghế đã bị người khác giữ: không ghế nào được giữ (all-or-nothing).""")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @PostMapping("/bookings")
    public ResponseEntity<ApiResponse<BookingResponse>> hold(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthUser user,
            @RequestHeader(IDEMPOTENCY_HEADER)
            @Pattern(regexp = "^[A-Za-z0-9_-]{8,40}$", message = "Idempotency-Key gồm 8–40 ký tự chữ, số, - hoặc _")
            String idempotencyKey,
            @Valid @RequestBody HoldSeatsRequest request) {
        HoldSeatsCommand command = new HoldSeatsCommand(user.id(), user.email(), request.showtimeId(),
                request.showtimeSeatIds(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(bookingService.holdSeats(command)));
    }

    @Operation(summary = "Xem đơn theo mã (chủ đơn hoặc admin)")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @GetMapping("/bookings/{code}")
    public ApiResponse<BookingResponse> get(@Parameter(hidden = true) @AuthenticationPrincipal AuthUser user,
                                            @PathVariable String code) {
        return ApiResponse.ok(bookingService.getBooking(code, user.id(), user.isAdmin()));
    }

    @Operation(summary = "Hủy đơn đang giữ chỗ và trả ghế")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @DeleteMapping("/bookings/{code}")
    public ApiResponse<BookingResponse> cancel(@Parameter(hidden = true) @AuthenticationPrincipal AuthUser user,
                                               @PathVariable String code) {
        return ApiResponse.ok(bookingService.cancel(code, user.id(), user.isAdmin()));
    }

    @Operation(summary = "Lịch sử đặt vé của tôi (mới nhất trước)")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @GetMapping("/me/bookings")
    public ApiResponse<PageResponse<BookingSummaryView>> myBookings(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(bookingService.myBookings(user.id(), page, size));
    }
}
