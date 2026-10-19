package com.ticketbox.catalog.web;

import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import com.ticketbox.catalog.dto.ShowDtos.ShowDetailResponse;
import com.ticketbox.catalog.dto.ShowDtos.ShowRequest;
import com.ticketbox.catalog.dto.ShowDtos.ShowSummaryResponse;
import com.ticketbox.catalog.service.ShowService;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.api.PageResponse;
import com.ticketbox.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Shows", description = "Quản lý phim/sự kiện")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/shows")
public class AdminShowController {

    private final ShowService showService;

    public AdminShowController(ShowService showService) {
        this.showService = showService;
    }

    @Operation(summary = "Danh sách show (gồm cả DRAFT)")
    @GetMapping
    public ApiResponse<PageResponse<ShowSummaryResponse>> search(
            @RequestParam(required = false) ShowType type,
            @RequestParam(required = false) ShowStatus status,
            @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.ok(showService.searchAdmin(type, status, q, pageable));
    }

    @Operation(summary = "Chi tiết show theo id")
    @GetMapping("/{id}")
    public ApiResponse<ShowDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(showService.getById(id));
    }

    @Operation(summary = "Tạo show (mặc định DRAFT, slug tự sinh nếu bỏ trống)")
    @PostMapping
    public ResponseEntity<ApiResponse<ShowDetailResponse>> create(@Valid @RequestBody ShowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(showService.create(request)));
    }

    @Operation(summary = "Cập nhật show")
    @PutMapping("/{id}")
    public ApiResponse<ShowDetailResponse> update(@PathVariable Long id, @Valid @RequestBody ShowRequest request) {
        return ApiResponse.ok(showService.update(id, request));
    }

    @Operation(summary = "Xóa show chưa có suất chiếu")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        showService.delete(id);
        return ApiResponse.ok();
    }
}
