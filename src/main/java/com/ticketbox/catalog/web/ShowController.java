package com.ticketbox.catalog.web;

import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import com.ticketbox.catalog.dto.ShowDtos.ShowDetailResponse;
import com.ticketbox.catalog.dto.ShowDtos.ShowSummaryResponse;
import com.ticketbox.catalog.dto.ShowtimeDtos.PublicShowtimeResponse;
import com.ticketbox.catalog.service.ShowService;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Shows (public)", description = "Tra cứu phim/sự kiện và lịch chiếu, không cần đăng nhập")
@RestController
@RequestMapping("/api/v1/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @Operation(summary = "Danh sách phim/sự kiện, lọc theo loại, trạng thái (mặc định PUBLISHED) và từ khóa")
    @GetMapping
    public ApiResponse<PageResponse<ShowSummaryResponse>> search(
            @RequestParam(required = false) ShowType type,
            @RequestParam(required = false) ShowStatus status,
            @Parameter(description = "Tìm theo tên") @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20, sort = "releaseDate", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.ok(showService.searchPublic(type, status, q, pageable));
    }

    @Operation(summary = "Chi tiết phim/sự kiện theo slug")
    @GetMapping("/{slug}")
    public ApiResponse<ShowDetailResponse> getBySlug(@PathVariable String slug) {
        return ApiResponse.ok(showService.getPublicBySlug(slug));
    }

    @Operation(summary = "Lịch chiếu của phim trong một ngày (giờ Việt Nam), lọc theo thành phố")
    @GetMapping("/{id}/showtimes")
    public ApiResponse<List<PublicShowtimeResponse>> showtimes(
            @PathVariable Long id,
            @Parameter(description = "Mặc định hôm nay", example = "2026-10-20")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Tên thành phố của địa điểm, không phân biệt hoa thường", example = "Hà Nội")
            @RequestParam(required = false) String city) {
        return ApiResponse.ok(showService.findPublicShowtimes(id, date, city));
    }
}
