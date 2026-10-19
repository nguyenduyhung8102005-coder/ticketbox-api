package com.ticketbox.catalog.web;

import com.ticketbox.catalog.dto.VenueDtos.AuditoriumRequest;
import com.ticketbox.catalog.dto.VenueDtos.AuditoriumResponse;
import com.ticketbox.catalog.dto.VenueDtos.SeatLayoutRequest;
import com.ticketbox.catalog.dto.VenueDtos.SeatLayoutResponse;
import com.ticketbox.catalog.dto.VenueDtos.VenueRequest;
import com.ticketbox.catalog.dto.VenueDtos.VenueResponse;
import com.ticketbox.catalog.service.VenueService;
import com.ticketbox.common.api.ApiResponse;
import com.ticketbox.common.api.PageResponse;
import com.ticketbox.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin - Venues", description = "Quản lý địa điểm, phòng chiếu, sơ đồ ghế")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminVenueController {

    private final VenueService venueService;

    public AdminVenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @Operation(summary = "Tạo địa điểm (rạp, nhà hát...)")
    @PostMapping("/venues")
    public ResponseEntity<ApiResponse<VenueResponse>> createVenue(@Valid @RequestBody VenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(venueService.createVenue(request)));
    }

    @Operation(summary = "Danh sách địa điểm, lọc theo thành phố")
    @GetMapping("/venues")
    public ApiResponse<PageResponse<VenueResponse>> listVenues(
            @RequestParam(required = false) String city,
            @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return ApiResponse.ok(venueService.listVenues(city, pageable));
    }

    @Operation(summary = "Danh sách phòng chiếu của một địa điểm")
    @GetMapping("/venues/{venueId}/auditoriums")
    public ApiResponse<List<AuditoriumResponse>> listAuditoriums(@PathVariable Long venueId) {
        return ApiResponse.ok(venueService.listAuditoriums(venueId));
    }

    @Operation(summary = "Tạo phòng chiếu")
    @PostMapping("/auditoriums")
    public ResponseEntity<ApiResponse<AuditoriumResponse>> createAuditorium(
            @Valid @RequestBody AuditoriumRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(venueService.createAuditorium(request)));
    }

    @Operation(summary = "Tạo sơ đồ ghế hàng loạt cho phòng (chỉ tạo một lần)")
    @PostMapping("/auditoriums/{id}/seats")
    public ResponseEntity<ApiResponse<SeatLayoutResponse>> createSeatLayout(
            @PathVariable Long id, @Valid @RequestBody SeatLayoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(venueService.createSeatLayout(id, request)));
    }

    @Operation(summary = "Xem sơ đồ ghế của phòng")
    @GetMapping("/auditoriums/{id}/seats")
    public ApiResponse<SeatLayoutResponse> getSeatLayout(@PathVariable Long id) {
        return ApiResponse.ok(venueService.getSeatLayout(id));
    }
}
