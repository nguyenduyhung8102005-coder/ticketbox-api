package com.ticketbox.catalog.service;

import com.ticketbox.catalog.domain.Auditorium;
import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.Show;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.domain.TicketPrice;
import com.ticketbox.catalog.dto.ShowtimeDtos.CreateShowtimeRequest;
import com.ticketbox.catalog.dto.ShowtimeDtos.ShowtimeResponse;
import com.ticketbox.catalog.repository.AuditoriumRepository;
import com.ticketbox.catalog.repository.SeatRepository;
import com.ticketbox.catalog.repository.ShowRepository;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import com.ticketbox.catalog.repository.TicketPriceRepository;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.common.persistence.ConstraintViolations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShowtimeService {

    /**
     * Sinh trạng thái ghế cho suất chiếu bằng MỘT câu INSERT ... SELECT: dữ liệu không rời khỏi database,
     * phòng 300 ghế cũng chỉ một round-trip (nhanh hơn hẳn batch insert từ Java).
     */
    static final String GENERATE_SHOWTIME_SEATS_SQL = """
            INSERT INTO showtime_seats (showtime_id, seat_id)
            SELECT ?, s.id FROM seats s WHERE s.auditorium_id = ? AND s.active
            """;

    private static final Set<ShowtimeStatus> CREATABLE_STATUSES =
            EnumSet.of(ShowtimeStatus.SCHEDULED, ShowtimeStatus.ON_SALE);

    private static final String COUNT_ACTIVE_BOOKED_SEATS_SQL =
            "SELECT count(*) FROM showtime_seats WHERE showtime_id = ? AND status IN ('HELD', 'SOLD')";

    private final ShowRepository showRepository;
    private final AuditoriumRepository auditoriumRepository;
    private final SeatRepository seatRepository;
    private final ShowtimeRepository showtimeRepository;
    private final TicketPriceRepository ticketPriceRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ShowtimeService(ShowRepository showRepository,
                           AuditoriumRepository auditoriumRepository,
                           SeatRepository seatRepository,
                           ShowtimeRepository showtimeRepository,
                           TicketPriceRepository ticketPriceRepository,
                           JdbcTemplate jdbcTemplate,
                           Clock clock) {
        this.showRepository = showRepository;
        this.auditoriumRepository = auditoriumRepository;
        this.seatRepository = seatRepository;
        this.showtimeRepository = showtimeRepository;
        this.ticketPriceRepository = ticketPriceRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Tạo suất chiếu, bảng giá và toàn bộ showtime_seats trong CÙNG một transaction:
     * hoặc có đủ cả ba, hoặc không có gì (không bao giờ có suất chiếu "thiếu ghế").
     */
    @Transactional
    public ShowtimeResponse create(CreateShowtimeRequest request) {
        Show show = showRepository.findById(request.showId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND));
        if (show.getStatus() == ShowStatus.ENDED) {
            throw new BusinessException(ErrorCode.SHOW_NOT_SCHEDULABLE);
        }
        Auditorium auditorium = auditoriumRepository.findWithVenueById(request.auditoriumId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUDITORIUM_NOT_FOUND));

        Instant start = request.startTime();
        Instant end = resolveEndTime(request, show);
        validateTimes(start, end, request.saleOpenAt());

        ShowtimeStatus status = request.status() == null ? ShowtimeStatus.ON_SALE : request.status();
        if (!CREATABLE_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Suất chiếu mới chỉ có thể là SCHEDULED hoặc ON_SALE");
        }

        List<SeatType> seatTypes = seatRepository.findActiveSeatTypes(auditorium.getId());
        if (seatTypes.isEmpty()) {
            throw new BusinessException(ErrorCode.SEAT_LAYOUT_EMPTY);
        }
        ensurePricesCover(seatTypes, request.prices());

        Showtime showtime = Showtime.create(show, auditorium, start, end, status, request.saleOpenAt());
        try {
            // flush ngay để exclusion constraint được kiểm tra tại đây, không phải lúc commit
            showtimeRepository.saveAndFlush(showtime);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isViolationOf(e, ConstraintViolations.SHOWTIME_OVERLAP)) {
                throw new BusinessException(ErrorCode.SHOWTIME_OVERLAP);
            }
            throw e;
        }

        List<TicketPrice> prices = request.prices().entrySet().stream()
                .map(e -> TicketPrice.of(showtime.getId(), e.getKey(), e.getValue()))
                .toList();
        ticketPriceRepository.saveAll(prices);

        int generated = jdbcTemplate.update(GENERATE_SHOWTIME_SEATS_SQL, showtime.getId(), auditorium.getId());
        log.info("Tạo suất chiếu {} (phòng {}), sinh {} ghế", showtime.getId(), auditorium.getId(), generated);

        return ShowtimeResponse.from(showtime, prices, generated);
    }

    @Transactional(readOnly = true)
    public ShowtimeResponse getById(Long id) {
        Showtime showtime = loadWithDetails(id);
        return ShowtimeResponse.from(showtime, ticketPriceRepository.findByShowtimeId(id), countSeats(id));
    }

    /** Đổi trạng thái; không cho hủy suất đã có ghế đang giữ hoặc đã bán (cần quy trình hoàn tiền ở Giai đoạn 3). */
    @Transactional
    public ShowtimeResponse changeStatus(Long id, ShowtimeStatus target) {
        Showtime showtime = loadWithDetails(id);
        if (target == ShowtimeStatus.CANCELLED) {
            Integer booked = jdbcTemplate.queryForObject(COUNT_ACTIVE_BOOKED_SEATS_SQL, Integer.class, id);
            if (booked != null && booked > 0) {
                throw new BusinessException(ErrorCode.SHOWTIME_HAS_BOOKINGS);
            }
        }
        showtime.changeStatus(target);
        try {
            // Mở lại suất đã hủy có thể đụng exclusion constraint (suất khác đã chiếm khung giờ)
            showtimeRepository.saveAndFlush(showtime);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isViolationOf(e, ConstraintViolations.SHOWTIME_OVERLAP)) {
                throw new BusinessException(ErrorCode.SHOWTIME_OVERLAP);
            }
            throw e;
        }
        return ShowtimeResponse.from(showtime, ticketPriceRepository.findByShowtimeId(id), countSeats(id));
    }

    private Showtime loadWithDetails(Long id) {
        return showtimeRepository.findWithDetailsById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND));
    }

    private Integer countSeats(Long showtimeId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM showtime_seats WHERE showtime_id = ?", Integer.class, showtimeId);
    }

    private static Instant resolveEndTime(CreateShowtimeRequest request, Show show) {
        if (request.endTime() != null) {
            return request.endTime();
        }
        if (show.getDurationMin() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Show chưa có thời lượng, cần nhập endTime cho suất chiếu");
        }
        return request.startTime().plus(Duration.ofMinutes(show.getDurationMin()));
    }

    private void validateTimes(Instant start, Instant end, Instant saleOpenAt) {
        if (!start.isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Giờ bắt đầu phải ở tương lai");
        }
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Giờ kết thúc phải sau giờ bắt đầu");
        }
        if (saleOpenAt != null && !saleOpenAt.isBefore(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Giờ mở bán phải trước giờ bắt đầu");
        }
    }

    private static void ensurePricesCover(List<SeatType> seatTypes, Map<SeatType, BigDecimal> prices) {
        Set<SeatType> missing = seatTypes.stream()
                .filter(type -> prices == null || !prices.containsKey(type))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SeatType.class)));
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.TICKET_PRICE_MISSING,
                    "Thiếu giá vé cho loại ghế: " + missing);
        }
    }
}
