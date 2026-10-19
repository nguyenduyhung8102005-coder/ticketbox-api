package com.ticketbox.catalog.dto;

import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.domain.TicketPrice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ShowtimeDtos {

    private ShowtimeDtos() {
    }

    /**
     * @param endTime bỏ trống thì tính = startTime + thời lượng phim
     * @param status  SCHEDULED (chưa bán) hoặc ON_SALE (mặc định)
     * @param prices  giá theo loại ghế, bắt buộc có đủ các loại ghế đang có trong phòng
     */
    public record CreateShowtimeRequest(
            @NotNull @Schema(example = "1") Long showId,
            @NotNull @Schema(example = "1") Long auditoriumId,
            @NotNull @Schema(example = "2026-10-20T12:00:00Z") Instant startTime,
            Instant endTime,
            Instant saleOpenAt,
            ShowtimeStatus status,
            @NotEmpty
            @Schema(example = "{\"STANDARD\": 90000, \"VIP\": 120000}")
            Map<SeatType, @NotNull @PositiveOrZero BigDecimal> prices) {
    }

    public record UpdateShowtimeStatusRequest(@NotNull ShowtimeStatus status) {
    }

    public record ShowtimeResponse(
            Long id,
            Long showId,
            String showTitle,
            Long auditoriumId,
            String auditoriumName,
            Long venueId,
            String venueName,
            String city,
            Instant startTime,
            Instant endTime,
            Instant saleOpenAt,
            ShowtimeStatus status,
            Map<SeatType, BigDecimal> prices,
            Integer totalSeats) {

        /** Dựng response; showtime phải được nạp kèm show/auditorium/venue (findWithDetailsById). */
        public static ShowtimeResponse from(Showtime st, List<TicketPrice> prices, Integer totalSeats) {
            Map<SeatType, BigDecimal> priceMap = new EnumMap<>(SeatType.class);
            prices.forEach(p -> priceMap.put(p.getSeatType(), p.getPrice()));
            var auditorium = st.getAuditorium();
            var venue = auditorium.getVenue();
            return new ShowtimeResponse(st.getId(), st.getShow().getId(), st.getShow().getTitle(),
                    auditorium.getId(), auditorium.getName(), venue.getId(), venue.getName(), venue.getCity(),
                    st.getStartTime(), st.getEndTime(), st.getSaleOpenAt(), st.getStatus(), priceMap, totalSeats);
        }
    }

    /** Một suất trong lịch chiếu công khai của một phim. */
    public record PublicShowtimeResponse(Long id, Instant startTime, Instant endTime, ShowtimeStatus status,
                                         Long venueId, String venueName, String city, String address,
                                         Long auditoriumId, String auditoriumName) {

        public static PublicShowtimeResponse from(Showtime st) {
            var auditorium = st.getAuditorium();
            var venue = auditorium.getVenue();
            return new PublicShowtimeResponse(st.getId(), st.getStartTime(), st.getEndTime(), st.getStatus(),
                    venue.getId(), venue.getName(), venue.getCity(), venue.getAddress(),
                    auditorium.getId(), auditorium.getName());
        }
    }
}
