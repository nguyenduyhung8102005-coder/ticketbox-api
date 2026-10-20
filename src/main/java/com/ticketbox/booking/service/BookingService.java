package com.ticketbox.booking.service;

import com.ticketbox.booking.domain.Booking;
import com.ticketbox.booking.domain.SeatStatus;
import com.ticketbox.booking.dto.BookingDtos.BookedSeat;
import com.ticketbox.booking.dto.BookingDtos.BookingResponse;
import com.ticketbox.booking.dto.BookingDtos.HoldSeatsCommand;
import com.ticketbox.booking.dto.BookingDtos.SeatMapResponse;
import com.ticketbox.booking.dto.BookingDtos.SeatMapSeat;
import com.ticketbox.booking.repository.BookingRepository;
import com.ticketbox.booking.repository.BookingSummaryView;
import com.ticketbox.booking.repository.SeatView;
import com.ticketbox.booking.repository.ShowtimeSeatRepository;
import com.ticketbox.booking.service.SeatHoldCache.HoldResult;
import com.ticketbox.catalog.domain.SeatType;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import com.ticketbox.catalog.repository.TicketPriceRepository;
import com.ticketbox.common.api.PageResponse;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.common.persistence.ConstraintViolations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nghiệp vụ giữ ghế / đơn đặt vé.
 *
 * <pre>
 * POST /bookings [F7, F8]
 *   0. Idempotency: key đã xử lý → trả lại đúng đơn cũ
 *   1. Redis Lua: giữ TẤT CẢ ghế hoặc KHÔNG ghế nào, TTL 10 phút
 *        thất bại → 409 ngay, chưa mở transaction, chưa giữ connection DB nào
 *   2. PostgreSQL (1 transaction): tạo booking PENDING_PAYMENT
 *        + UPDATE showtime_seats ... WHERE status = 'AVAILABLE'
 *        số dòng cập nhật ≠ số ghế → rollback + nhả key Redis → 409
 *   3. 201 Created, expires_at = now + 10 phút
 * </pre>
 * <p>
 * Không đặt @Transactional ở mức method: bước Redis phải chạy NGOÀI transaction để request thua
 * không chiếm connection pool. Transaction được mở bằng {@link TransactionTemplate} chỉ cho bước 2.
 */
@Slf4j
@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final ShowtimeRepository showtimeRepository;
    private final TicketPriceRepository ticketPriceRepository;
    private final SeatHoldCache seatHoldCache;
    private final BookingCodeGenerator codeGenerator;
    private final BookingProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public BookingService(BookingRepository bookingRepository,
                          ShowtimeSeatRepository showtimeSeatRepository,
                          ShowtimeRepository showtimeRepository,
                          TicketPriceRepository ticketPriceRepository,
                          SeatHoldCache seatHoldCache,
                          BookingCodeGenerator codeGenerator,
                          BookingProperties properties,
                          Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.showtimeSeatRepository = showtimeSeatRepository;
        this.showtimeRepository = showtimeRepository;
        this.ticketPriceRepository = ticketPriceRepository;
        this.seatHoldCache = seatHoldCache;
        this.codeGenerator = codeGenerator;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    // ===================================================================================
    // Giữ ghế
    // ===================================================================================

    public BookingResponse holdSeats(HoldSeatsCommand command) {
        List<Long> seatIds = normalizeSeatIds(command.showtimeSeatIds());
        String idempotencyKey = scopedIdempotencyKey(command.userId(), command.idempotencyKey());

        // 0. Client gửi lại request (mạng chập chờn, bấm 2 lần): trả lại đơn đã tạo, không tạo đơn mới
        Optional<BookingResponse> replay = findReplay(idempotencyKey, command.showtimeId(), seatIds);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 1. Lớp Redis. Mã đơn dùng luôn làm token sở hữu key, để sau này hủy/hết hạn nhả đúng key của mình.
        String bookingCode = codeGenerator.next();
        HoldResult hold = seatHoldCache.tryHoldAll(command.showtimeId(), seatIds, bookingCode, properties.holdTtl());
        if (hold == HoldResult.REJECTED) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN);
        }

        // 2. Lớp PostgreSQL. Lỗi ở BẤT KỲ đâu (kể cả lúc commit) đều nhả key Redis của mình,
        //    nếu không ghế sẽ bị "kẹt" trong Redis 10 phút dù DB đã rollback.
        try {
            return tx.execute(status -> createHeldBooking(command, seatIds, bookingCode, idempotencyKey));
        } catch (RuntimeException e) {
            if (hold == HoldResult.ACQUIRED) {
                seatHoldCache.releaseAll(command.showtimeId(), seatIds, bookingCode);
            }
            // Hai request cùng Idempotency-Key chạy song song: request thua vấp unique constraint,
            // trả về đơn mà request thắng vừa tạo
            if (e instanceof DataIntegrityViolationException
                    && ConstraintViolations.isViolationOf(e, ConstraintViolations.BOOKING_IDEMPOTENCY_KEY)) {
                return findReplay(idempotencyKey, command.showtimeId(), seatIds).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    private BookingResponse createHeldBooking(HoldSeatsCommand command, List<Long> seatIds,
                                              String bookingCode, String idempotencyKey) {
        Instant now = clock.instant();
        Showtime showtime = showtimeRepository.findWithDetailsById(command.showtimeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND));
        if (!showtime.isOnSaleAt(now)) {
            throw new BusinessException(ErrorCode.SHOWTIME_NOT_ON_SALE);
        }

        List<SeatView> seats = showtimeSeatRepository.findSeatViews(showtime.getId(), seatIds);
        if (seats.size() != seatIds.size()) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }
        // Kiểm tra sớm cho trường hợp phổ biến (ghế đã bán từ trước), đỡ phải INSERT booking rồi rollback.
        // Đây KHÔNG phải cơ chế chống tranh chấp: giữa lúc đọc và lúc ghi vẫn có thể bị chen, việc đó do UPDATE lo.
        if (seats.stream().anyMatch(s -> s.status() != SeatStatus.AVAILABLE)) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN);
        }

        Map<SeatType, BigDecimal> prices = priceMap(showtime.getId());
        List<BookedSeat> bookedSeats = seats.stream()
                .sorted(Comparator.comparing(SeatView::rowLabel).thenComparing(SeatView::seatNumber))
                .map(s -> new BookedSeat(s.showtimeSeatId(), s.label(), s.seatType(), priceOf(prices, s.seatType()),
                        null))
                .toList();
        BigDecimal total = bookedSeats.stream().map(BookedSeat::price).reduce(BigDecimal.ZERO, BigDecimal::add);

        Instant expiresAt = now.plus(properties.holdTtl());
        Booking booking = bookingRepository.save(Booking.pending(bookingCode, command.userId(),
                command.customerEmail(), showtime.getId(), total, expiresAt, idempotencyKey));

        // Nguồn sự thật: UPDATE có điều kiện, nguyên tử ở mức từng dòng
        int updated = showtimeSeatRepository.holdSeats(showtime.getId(), seatIds, booking.getId(), expiresAt);
        if (updated != seatIds.size()) {
            log.debug("Giữ ghế thất bại ở DB: yêu cầu {}, giữ được {} (đơn {})", seatIds.size(), updated, bookingCode);
            throw new BusinessException(ErrorCode.SEAT_ALREADY_TAKEN);   // -> rollback cả booking lẫn các ghế đã cập nhật
        }

        bookedSeats.forEach(s ->
                booking.addItem(showtimeSeatRepository.getReferenceById(s.showtimeSeatId()), s.price()));
        log.info("Đơn {} giữ {} ghế suất {} tới {}", bookingCode, seatIds.size(), showtime.getId(), expiresAt);
        return toResponse(booking, showtime, bookedSeats);
    }

    // ===================================================================================
    // Xem / hủy đơn
    // ===================================================================================

    /** Người khác (không phải chủ đơn, không phải admin) nhận 404 thay vì 403 để không lộ mã đơn tồn tại. */
    public BookingResponse getBooking(String bookingCode, Long userId, boolean admin) {
        return readOnlyTx.execute(status -> {
            Booking booking = bookingRepository.findByBookingCode(bookingCode)
                    .filter(b -> admin || b.isOwnedBy(userId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.BOOKING_NOT_FOUND));
            return toResponse(booking);
        });
    }

    /** Hủy đơn đang chờ thanh toán và trả ghế. Khóa dòng đơn để không chạy song song với job hết hạn. */
    public BookingResponse cancel(String bookingCode, Long userId, boolean admin) {
        CancelOutcome outcome = tx.execute(status -> {
            Booking booking = bookingRepository.findByCodeForUpdate(bookingCode)
                    .filter(b -> admin || b.isOwnedBy(userId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.BOOKING_NOT_FOUND));
            if (!booking.isPendingPayment()) {
                throw new BusinessException(ErrorCode.BOOKING_NOT_CANCELLABLE);
            }
            List<Long> seatIds = booking.showtimeSeatIds();
            showtimeSeatRepository.releaseHeldSeats(booking.getId());
            booking.cancel();
            bookingRepository.flush();
            return new CancelOutcome(booking.getShowtimeId(), seatIds, booking.getBookingCode(), toResponse(booking));
        });
        // Sau khi DB đã commit mới nhả Redis: nếu nhả trước mà commit lỗi, ghế sẽ "trống" ở Redis nhưng vẫn HELD ở DB
        seatHoldCache.releaseAll(outcome.showtimeId(), outcome.seatIds(), outcome.bookingCode());
        return outcome.response();
    }

    public PageResponse<BookingSummaryView> myBookings(Long userId, int page, int size) {
        return readOnlyTx.execute(status ->
                PageResponse.from(bookingRepository.findSummariesByUserId(userId, PageRequest.of(page, size))));
    }

    // ===================================================================================
    // Sơ đồ ghế
    // ===================================================================================

    public SeatMapResponse seatMap(Long showtimeId) {
        return readOnlyTx.execute(status -> {
            Showtime showtime = showtimeRepository.findWithDetailsById(showtimeId)
                    .filter(st -> st.getShow().isVisibleToPublic())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND));
            Map<SeatType, BigDecimal> prices = priceMap(showtimeId);
            List<SeatMapSeat> seats = showtimeSeatRepository.findSeatMap(showtimeId).stream()
                    .map(s -> new SeatMapSeat(s.showtimeSeatId(), s.rowLabel(), s.seatNumber(), s.label(),
                            s.seatType(), s.status(), prices.get(s.seatType())))
                    .toList();
            int available = (int) seats.stream().filter(s -> s.status() == SeatStatus.AVAILABLE).count();
            return new SeatMapResponse(showtime.getId(), showtime.getShow().getTitle(), showtime.getStartTime(),
                    showtime.getStatus(), showtime.getAuditorium().getVenue().getName(),
                    showtime.getAuditorium().getName(), prices, available, seats);
        });
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    private Optional<BookingResponse> findReplay(String idempotencyKey, Long showtimeId, List<Long> seatIds) {
        return readOnlyTx.execute(status -> bookingRepository.findByIdempotencyKey(idempotencyKey).map(booking -> {
            List<Long> previousSeatIds = booking.showtimeSeatIds().stream().sorted().toList();
            if (!booking.getShowtimeId().equals(showtimeId) || !previousSeatIds.equals(seatIds)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return toResponse(booking);
        }));
    }

    private BookingResponse toResponse(Booking booking) {
        Showtime showtime = showtimeRepository.findWithDetailsById(booking.getShowtimeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND));
        List<BookedSeat> seats = bookingRepository.findBookedSeats(booking.getId()).stream()
                .map(v -> new BookedSeat(v.showtimeSeatId(), v.label(), v.seatType(), v.price(), v.ticketCode()))
                .toList();
        return toResponse(booking, showtime, seats);
    }

    private static BookingResponse toResponse(Booking booking, Showtime showtime, List<BookedSeat> seats) {
        return new BookingResponse(
                booking.getBookingCode(),
                booking.getStatus(),
                showtime.getId(),
                showtime.getShow().getTitle(),
                showtime.getStartTime(),
                showtime.getAuditorium().getVenue().getName(),
                showtime.getAuditorium().getName(),
                seats,
                booking.getTotalAmount(),
                booking.getExpiresAt(),
                booking.getCreatedAt(),
                booking.getConfirmedAt());
    }

    private Map<SeatType, BigDecimal> priceMap(Long showtimeId) {
        Map<SeatType, BigDecimal> prices = new EnumMap<>(SeatType.class);
        ticketPriceRepository.findByShowtimeId(showtimeId).forEach(p -> prices.put(p.getSeatType(), p.getPrice()));
        return prices;
    }

    private static BigDecimal priceOf(Map<SeatType, BigDecimal> prices, SeatType type) {
        BigDecimal price = prices.get(type);
        if (price == null) {
            throw new BusinessException(ErrorCode.TICKET_PRICE_MISSING, "Suất chiếu chưa có giá cho ghế " + type);
        }
        return price;
    }

    /** Bỏ trùng, sắp xếp (để so khớp idempotency và để khóa dòng theo thứ tự cố định), kiểm tra giới hạn. */
    List<Long> normalizeSeatIds(List<Long> showtimeSeatIds) {
        if (showtimeSeatIds == null || showtimeSeatIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cần chọn ít nhất một ghế");
        }
        List<Long> normalized = showtimeSeatIds.stream().distinct().sorted().toList();
        if (normalized.size() != showtimeSeatIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Danh sách ghế bị lặp");
        }
        if (normalized.size() > properties.maxSeatsPerBooking()) {
            throw new BusinessException(ErrorCode.SEAT_LIMIT_EXCEEDED,
                    "Mỗi đơn tối đa " + properties.maxSeatsPerBooking() + " ghế");
        }
        return normalized;
    }

    /** Gắn userId vào key: hai người dùng vô tình gửi cùng một key không đụng nhau. */
    static String scopedIdempotencyKey(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Thiếu header Idempotency-Key");
        }
        return "u" + userId + ":" + idempotencyKey.trim();
    }

    private record CancelOutcome(Long showtimeId, List<Long> seatIds, String bookingCode, BookingResponse response) {
    }
}
