package com.ticketbox.catalog.service;

import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.dto.ShowtimeDtos.CreateShowtimeRequest;
import com.ticketbox.catalog.dto.ShowtimeDtos.ShowtimeResponse;
import com.ticketbox.catalog.repository.AuditoriumRepository;
import com.ticketbox.catalog.repository.SeatRepository;
import com.ticketbox.catalog.repository.ShowRepository;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import com.ticketbox.catalog.repository.TicketPriceRepository;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ticketbox.catalog.CatalogTestData.auditorium;
import static com.ticketbox.catalog.CatalogTestData.show;
import static com.ticketbox.catalog.CatalogTestData.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowtimeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final Instant START = Instant.parse("2026-10-25T12:00:00Z");

    @Mock
    private ShowRepository showRepository;
    @Mock
    private AuditoriumRepository auditoriumRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private ShowtimeRepository showtimeRepository;
    @Mock
    private TicketPriceRepository ticketPriceRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private ShowtimeService service;

    @BeforeEach
    void setUp() {
        service = new ShowtimeService(showRepository, auditoriumRepository, seatRepository, showtimeRepository,
                ticketPriceRepository, jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_valid_savesShowtimeAndGeneratesSeatsWithInsertSelect() {
        givenShowAndAuditorium();
        when(seatRepository.findActiveSeatTypes(5L)).thenReturn(List.of(SeatType.STANDARD, SeatType.VIP));
        when(showtimeRepository.saveAndFlush(any(Showtime.class))).thenAnswer(inv -> withId(inv.getArgument(0), 10L));
        when(jdbcTemplate.update(eq(ShowtimeService.GENERATE_SHOWTIME_SEATS_SQL), eq(10L), eq(5L))).thenReturn(120);

        ShowtimeResponse response = service.create(request(Map.of(
                SeatType.STANDARD, new BigDecimal("90000"),
                SeatType.VIP, new BigDecimal("120000"))));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.totalSeats()).isEqualTo(120);
        assertThat(response.status()).isEqualTo(ShowtimeStatus.ON_SALE);
        // Không gửi endTime: tính từ thời lượng phim 120 phút
        assertThat(response.endTime()).isEqualTo(START.plusSeconds(120 * 60));
        assertThat(response.prices()).containsEntry(SeatType.VIP, new BigDecimal("120000"));
    }

    @Test
    void create_overlappingShowtime_isMappedToShowtimeOverlap() {
        givenShowAndAuditorium();
        when(seatRepository.findActiveSeatTypes(5L)).thenReturn(List.of(SeatType.STANDARD));
        when(showtimeRepository.saveAndFlush(any(Showtime.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("conflicting key value violates exclusion constraint \"ex_showtimes_no_overlap\"")));

        assertThatThrownBy(() -> service.create(request(Map.of(SeatType.STANDARD, new BigDecimal("90000")))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOWTIME_OVERLAP);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void create_otherIntegrityError_isNotSwallowed() {
        givenShowAndAuditorium();
        when(seatRepository.findActiveSeatTypes(5L)).thenReturn(List.of(SeatType.STANDARD));
        when(showtimeRepository.saveAndFlush(any(Showtime.class)))
                .thenThrow(new DataIntegrityViolationException("violates check constraint \"ck_showtimes_time\""));

        assertThatThrownBy(() -> service.create(request(Map.of(SeatType.STANDARD, new BigDecimal("90000")))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void create_missingPriceForSeatType_isRejected() {
        givenShowAndAuditorium();
        when(seatRepository.findActiveSeatTypes(5L)).thenReturn(List.of(SeatType.STANDARD, SeatType.VIP));

        assertThatThrownBy(() -> service.create(request(Map.of(SeatType.STANDARD, new BigDecimal("90000")))))
                .extracting("errorCode").isEqualTo(ErrorCode.TICKET_PRICE_MISSING);
        verify(showtimeRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_auditoriumWithoutSeats_isRejected() {
        givenShowAndAuditorium();
        when(seatRepository.findActiveSeatTypes(5L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(request(Map.of(SeatType.STANDARD, BigDecimal.ONE))))
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_LAYOUT_EMPTY);
    }

    @Test
    void create_inThePast_isRejected() {
        givenShowAndAuditorium();
        var past = new CreateShowtimeRequest(1L, 5L, NOW.minusSeconds(60), null, null, null,
                Map.of(SeatType.STANDARD, BigDecimal.ONE));

        assertThatThrownBy(() -> service.create(past))
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void create_forEndedShow_isRejected() {
        when(showRepository.findById(1L)).thenReturn(Optional.of(show(1L, ShowStatus.ENDED)));

        assertThatThrownBy(() -> service.create(request(Map.of(SeatType.STANDARD, BigDecimal.ONE))))
                .extracting("errorCode").isEqualTo(ErrorCode.SHOW_NOT_SCHEDULABLE);
    }

    @Test
    void changeStatus_cancelShowtimeWithHeldSeats_isRejected() {
        Showtime showtime = withId(Showtime.create(show(1L, ShowStatus.PUBLISHED), auditorium(5L), START,
                START.plusSeconds(7200), ShowtimeStatus.ON_SALE, null), 10L);
        when(showtimeRepository.findWithDetailsById(10L)).thenReturn(Optional.of(showtime));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(10L))).thenReturn(3);

        assertThatThrownBy(() -> service.changeStatus(10L, ShowtimeStatus.CANCELLED))
                .extracting("errorCode").isEqualTo(ErrorCode.SHOWTIME_HAS_BOOKINGS);
    }

    @Test
    void changeStatus_fromFinished_isInvalidTransition() {
        Showtime showtime = withId(Showtime.create(show(1L, ShowStatus.PUBLISHED), auditorium(5L), START,
                START.plusSeconds(7200), ShowtimeStatus.FINISHED, null), 10L);
        when(showtimeRepository.findWithDetailsById(10L)).thenReturn(Optional.of(showtime));

        assertThatThrownBy(() -> service.changeStatus(10L, ShowtimeStatus.ON_SALE))
                .extracting("errorCode").isEqualTo(ErrorCode.SHOWTIME_STATUS_TRANSITION_INVALID);
    }

    private void givenShowAndAuditorium() {
        when(showRepository.findById(1L)).thenReturn(Optional.of(show(1L, ShowStatus.PUBLISHED)));
        when(auditoriumRepository.findWithVenueById(5L)).thenReturn(Optional.of(auditorium(5L)));
    }

    private static CreateShowtimeRequest request(Map<SeatType, BigDecimal> prices) {
        return new CreateShowtimeRequest(1L, 5L, START, null, null, null, prices);
    }
}
