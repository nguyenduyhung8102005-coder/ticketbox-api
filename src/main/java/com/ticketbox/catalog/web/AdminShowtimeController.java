package com.ticketbox.catalog.web;

import com.ticketbox.catalog.dto.ShowtimeDtos.CreateShowtimeRequest;
import com.ticketbox.catalog.dto.ShowtimeDtos.ShowtimeResponse;
import com.ticketbox.catalog.dto.ShowtimeDtos.UpdateShowtimeStatusRequest;
import com.ticketbox.catalog.service.ShowtimeService;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Showtimes", description = "Quản lý suất chiếu")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/showtimes")
public class AdminShowtimeController {

    private final ShowtimeService showtimeService;

    public AdminShowtimeController(ShowtimeService showtimeService) {
        this.showtimeService = showtimeService;
    }

    @Operation(summary = "Tạo suất chiếu + bảng giá + sinh trạng thái ghế (một transaction)",
            description = "Trả 409 SHOWTIME_OVERLAP nếu phòng đã có suất khác giao thời gian")
    @PostMapping
    public ResponseEntity<ApiResponse<ShowtimeResponse>> create(@Valid @RequestBody CreateShowtimeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(showtimeService.create(request)));
    }

    @Operation(summary = "Chi tiết suất chiếu")
    @GetMapping("/{id}")
    public ApiResponse<ShowtimeResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(showtimeService.getById(id));
    }

    @Operation(summary = "Đổi trạng thái suất chiếu (mở bán, tạm dừng, hủy, kết thúc)")
    @PatchMapping("/{id}/status")
    public ApiResponse<ShowtimeResponse> changeStatus(@PathVariable Long id,
                                                      @Valid @RequestBody UpdateShowtimeStatusRequest request) {
        return ApiResponse.ok(showtimeService.changeStatus(id, request.status()));
    }
}
