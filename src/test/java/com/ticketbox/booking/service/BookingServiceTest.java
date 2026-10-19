package com.ticketbox.booking.service;

import com.ticketbox.booking.domain.Booking;
import com.ticketbox.booking.domain.BookingStatus;
import com.ticketbox.booking.domain.SeatStatus;
import com.ticketbox.booking.domain.ShowtimeSeat;
import com.ticketbox.booking.dto.BookingDtos.BookingResponse;
import com.ticketbox.booking.dto.BookingDtos.HoldSeatsCommand;
import com.ticketbox.booking.repository.BookedSeatView;
import com.ticketbox.booking.repository.BookingRepository;
import com.ticketbox.booking.repository.SeatView;
import com.ticketbox.booking.repository.ShowtimeSeatRepository;
import com.ticketbox.booking.service.SeatHoldCache.HoldResult;
import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.domain.TicketPrice;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import com.ticketbox.catalog.repository.TicketPriceRepository;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.ticketbox.catalog.CatalogTestData.showtime;
import static com.ticketbox.catalog.CatalogTestData.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kiểm tra luồng giữ ghế ở mức logic (không DB, không Redis thật). Tính đúng đắn khi chạy đồng thời
 * được chứng minh riêng trong BookingConcurrencyIT với PostgreSQL và Redis thật.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final Duration HOLD_TTL = Duration.ofMinutes(10);
    private static final Long SHOWTIME_ID = 1L;
    private static final List<Long> SEATS = List.of(11L, 12L);
    private static final String CODE = "TBTESTCODE01";

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ShowtimeSeatRepository showtimeSeatRepository;
    @Mock
    private ShowtimeRepository showtimeRepository;
    @Mock
    private TicketPriceRepository ticketPriceRepository;
    @Mock
    private SeatHoldCache seatHoldCache;
    @Mock
    private BookingCodeGenerator codeGenerator;
    @Mock
    private PlatformTransactionManager transactionManager;

    private BookingService service;

    @BeforeEach
    void setUp() {
        BookingProperties properties = new BookingProperties(HOLD_TTL, 8,
                new BookingProperties.ExpiryJob(true, Duration.ofSeconds(30), 100));
        service = new BookingService(bookingRepository, showtimeSeatRepository, showtimeRepository,
                ticketPriceRepository, seatHoldCache, codeGenerator, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    @Test
    void hold_success_createsPendingBookingWithTotalAndExpiry() {
        givenNoPreviousRequest();
        givenRedis(HoldResult.ACQUIRED);
        givenShowtimeOnSaleWithAvailableSeats();
        givenBookingSaved();
        when(showtimeSeatRepository.holdSeats(eq(SHOWTIME_ID), eq(SEATS), eq(100L), eq(NOW.plus(HOLD_TTL))))
                .thenReturn(2);
        when(showtimeSeatRepository.getReferenceById(anyLong())).thenReturn(mock(ShowtimeSeat.class));

        BookingResponse response = service.holdSeats(command(List.of(12L, 11L)));

        assertThat(response.bookingCode()).isEqualTo(CODE);
        assertThat(response.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(response.totalAmount()).isEqualByComparingTo("210000");   // 90.000 + 120.000
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(HOLD_TTL));
        assertThat(response.seats()).extracting("label").containsExactly("F7", "F8");
        verify(seatHoldCache, never()).releaseAll(any(), any(), any());
    }

    @Test
    void hold_redisRejects_failsFastWithoutTouchingDatabaseWrites() {
        givenNoPreviousRequest();
        givenRedis(HoldResult.REJECTED);

        assertThatThrownBy(() -> service.holdSeats(command(SEATS)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_ALREADY_TAKEN);

        verifyNoInteractions(showtimeSeatRepository, showtimeRepository);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void hold_databaseUpdatesFewerRows_rollsBackAndReleasesRedisKeys() {
        givenNoPreviousRequest();
        givenRedis(HoldResult.ACQUIRED);
        givenShowtimeOnSaleWithAvailableSeats();
        givenBookingSaved();
        // Redis cho qua nhưng DB thấy 1 ghế đã bị lấy (ví dụ Redis vừa restart, mất key)
        when(showtimeSeatRepository.holdSeats(eq(SHOWTIME_ID), eq(SEATS), eq(100L), any())).thenReturn(1);

        assertThatThrownBy(() -> service.holdSeats(command(SEATS)))
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_ALREADY_TAKEN);

        verify(transactionManager).rollback(any());
        verify(seatHoldCache).releaseAll(SHOWTIME_ID, SEATS, CODE);
    }

    @Test
    void hold_redisUnavailable_fallsBackToDatabaseOnly() {
        givenNoPreviousRequest();
        givenRedis(HoldResult.UNAVAILABLE);
        givenShowtimeOnSaleWithAvailableSeats();
        givenBookingSaved();
        when(showtimeSeatRepository.holdSeats(eq(SHOWTIME_ID), eq(SEATS), eq(100L), any())).thenReturn(1);

        assertThatThrownBy(() -> service.holdSeats(command(SEATS)))
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_ALREADY_TAKEN);

        // Không giữ được key nào trong Redis thì cũng không có gì để nhả
        verify(seatHoldCache, never()).releaseAll(any(), any(), any());
    }

    @Test
    void hold_showtimeNotOnSale_isRejectedAndRedisReleased() {
        givenNoPreviousRequest();
        givenRedis(HoldResult.ACQUIRED);
        Showtime scheduled = showtime(SHOWTIME_ID, NOW.plus(Duration.ofDays(2)), ShowtimeStatus.SCHEDULED);
        when(showtimeRepository.findWithDetailsById(SHOWTIME_ID)).thenReturn(Optional.of(scheduled));

        assertThatThrownBy(() -> service.holdSeats(command(SEATS)))
                .extracting("errorCode").isEqualTo(ErrorCode.SHOWTIME_NOT_ON_SALE);
        verify(seatHoldCache).releaseAll(SHOWTIME_ID, SEATS, CODE);
    }

    @Test
    void hold_seatFromAnotherShowtime_isRejected() {
        givenNoPreviousRequest();
        givenRedis(HoldResult.ACQUIRED);
        when(showtimeRepository.findWithDetailsById(SHOWTIME_ID))
                .thenReturn(Optional.of(showtime(SHOWTIME_ID, NOW.plus(Duration.ofDays(2)), ShowtimeStatus.ON_SALE)));
        // Chỉ 1 trong 2 ghế thuộc suất này
        when(showtimeSeatRepository.findSeatViews(SHOWTIME_ID, SEATS))
                .thenReturn(List.of(new SeatView(11L, "F", 7, SeatType.STANDARD, SeatStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.holdSeats(command(SEATS)))
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_NOT_FOUND);
    }

    @Test
    void hold_tooManySeats_isRejectedBeforeAnything() {
        List<Long> nine = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        assertThatThrownBy(() -> service.holdSeats(command(nine)))
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_LIMIT_EXCEEDED);
        verifyNoInteractions(seatHoldCache, bookingRepository);
    }

    @Test
    void hold_duplicateSeats_isRejected() {
        assertThatThrownBy(() -> service.holdSeats(command(List.of(11L, 11L))))
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void hold_sameIdempotencyKeyAgain_returnsExistingBookingWithoutHoldingAgain() {
        Booking existing = existingBooking(SEATS);
        when(bookingRepository.findByIdempotencyKey("u7:key-12345678")).thenReturn(Optional.of(existing));
        when(showtimeRepository.findWithDetailsById(SHOWTIME_ID))
                .thenReturn(Optional.of(showtime(SHOWTIME_ID, NOW.plus(Duration.ofDays(2)), ShowtimeStatus.ON_SALE)));
        when(bookingRepository.findBookedSeats(100L)).thenReturn(List.of(
                new BookedSeatView(11L, "F", 7, SeatType.STANDARD, new BigDecimal("90000")),
                new BookedSeatView(12L, "F", 8, SeatType.VIP, new BigDecimal("120000"))));

        BookingResponse response = service.holdSeats(command(SEATS));

        assertThat(response.bookingCode()).isEqualTo("TBEXISTING01");
        verifyNoInteractions(seatHoldCache);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void hold_sameIdempotencyKeyWithDifferentSeats_isRejected() {
        Booking existing = existingBooking(List.of(11L, 13L));
        when(bookingRepository.findByIdempotencyKey("u7:key-12345678")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.holdSeats(command(SEATS)))
                .extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        verifyNoInteractions(seatHoldCache);
    }

    @Test
    void scopedIdempotencyKey_isPrefixedWithUserId() {
        assertThat(BookingService.scopedIdempotencyKey(7L, " abc-12345 ")).isEqualTo("u7:abc-12345");
    }

    // ----- helpers -----

    private static HoldSeatsCommand command(List<Long> seats) {
        return new HoldSeatsCommand(7L, "an@gmail.com", SHOWTIME_ID, seats, "key-12345678");
    }

    private void givenNoPreviousRequest() {
        when(bookingRepository.findByIdempotencyKey("u7:key-12345678")).thenReturn(Optional.empty());
    }

    private void givenRedis(HoldResult result) {
        when(codeGenerator.next()).thenReturn(CODE);
        when(seatHoldCache.tryHoldAll(SHOWTIME_ID, SEATS, CODE, HOLD_TTL)).thenReturn(result);
    }

    private void givenShowtimeOnSaleWithAvailableSeats() {
        when(showtimeRepository.findWithDetailsById(SHOWTIME_ID))
                .thenReturn(Optional.of(showtime(SHOWTIME_ID, NOW.plus(Duration.ofDays(2)), ShowtimeStatus.ON_SALE)));
        when(showtimeSeatRepository.findSeatViews(SHOWTIME_ID, SEATS)).thenReturn(List.of(
                new SeatView(12L, "F", 8, SeatType.VIP, SeatStatus.AVAILABLE),
                new SeatView(11L, "F", 7, SeatType.STANDARD, SeatStatus.AVAILABLE)));
        when(ticketPriceRepository.findByShowtimeId(SHOWTIME_ID)).thenReturn(List.of(
                TicketPrice.of(SHOWTIME_ID, SeatType.STANDARD, new BigDecimal("90000")),
                TicketPrice.of(SHOWTIME_ID, SeatType.VIP, new BigDecimal("120000"))));
    }

    private void givenBookingSaved() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100L));
    }

    private static Booking existingBooking(List<Long> seatIds) {
        Booking booking = withId(Booking.pending("TBEXISTING01", 7L, "an@gmail.com", SHOWTIME_ID,
                new BigDecimal("210000"), NOW.plus(HOLD_TTL), "u7:key-12345678"), 100L);
        for (Long id : seatIds) {
            ShowtimeSeat seat = mock(ShowtimeSeat.class);
            when(seat.getId()).thenReturn(id);
            booking.addItem(seat, new BigDecimal("90000"));
        }
        return booking;
    }
}
