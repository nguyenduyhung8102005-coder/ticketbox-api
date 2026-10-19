package com.ticketbox.catalog.dto;

import com.ticketbox.catalog.domain.Auditorium;
import com.ticketbox.catalog.domain.Seat;
import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.Venue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class VenueDtos {

    private VenueDtos() {
    }

    public record VenueRequest(
            @NotBlank @Size(max = 255) @Schema(example = "CGV Vincom Bà Triệu") String name,
            @NotBlank @Size(max = 100) @Schema(example = "Hà Nội") String city,
            @NotBlank @Size(max = 500) @Schema(example = "191 Bà Triệu, Hai Bà Trưng") String address) {
    }

    public record VenueResponse(Long id, String name, String city, String address) {

        public static VenueResponse from(Venue v) {
            return new VenueResponse(v.getId(), v.getName(), v.getCity(), v.getAddress());
        }
    }

    public record AuditoriumRequest(
            @NotNull @Schema(example = "1") Long venueId,
            @NotBlank @Size(max = 100) @Schema(example = "Phòng 1") String name) {
    }

    public record AuditoriumResponse(Long id, Long venueId, String venueName, String name) {

        public static AuditoriumResponse from(Auditorium a) {
            return new AuditoriumResponse(a.getId(), a.getVenue().getId(), a.getVenue().getName(), a.getName());
        }
    }

    /**
     * Tạo sơ đồ ghế hàng loạt theo hàng. Ví dụ: hàng A–E mỗi hàng 12 ghế thường, hàng F–G 12 ghế VIP.
     */
    public record SeatLayoutRequest(
            @NotEmpty @Size(max = 52) List<@Valid @NotNull RowSpec> rows) {
    }

    public record RowSpec(
            @NotBlank @Pattern(regexp = "^[A-Z]{1,3}$", message = "Tên hàng là 1–3 chữ in hoa")
            @Schema(example = "A") String rowLabel,
            @NotNull @Min(1) @Max(100) @Schema(example = "12") Integer seatCount,
            @Schema(example = "STANDARD", description = "Bỏ trống = STANDARD") SeatType seatType) {

        public SeatType seatTypeOrDefault() {
            return seatType == null ? SeatType.STANDARD : seatType;
        }
    }

    public record SeatResponse(Long id, String rowLabel, Integer seatNumber, SeatType seatType, boolean active) {

        public static SeatResponse from(Seat s) {
            return new SeatResponse(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.getSeatType(), s.isActive());
        }
    }

    public record SeatLayoutResponse(Long auditoriumId, int totalSeats, List<SeatResponse> seats) {
    }
}
