package com.ticketbox.booking.api;

import com.ticketbox.booking.domain.Booking;
import com.ticketbox.booking.domain.BookingItem;
import com.ticketbox.booking.domain.BookingStatus;
import com.ticketbox.booking.domain.SeatStatus;
import com.ticketbox.booking.domain.ShowtimeSeat;
import com.ticketbox.booking.repository.BookedSeatView;
import com.ticketbox.booking.repository.BookingRepository;
import com.ticketbox.booking.repository.ShowtimeSeatRepository;
import com.ticketbox.booking.ticket.TicketClaims;
import com.ticketbox.booking.ticket.TicketCodeSigner;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import com.ticketbox.common.events.BookingConfirmedEvent;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.common.messaging.EventTypes;
import com.ticketbox.common.outbox.OutboxWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * API công khai của module booking dành cho module payment (ranh giới module: payment không đụng vào
 * entity/repository của booking, chỉ gọi qua lớp này).
 * <p>
 * Các thao tác ghi dùng {@code Propagation.MANDATORY}: bắt buộc chạy TRONG transaction của bên gọi, để
 * "ghi nhận thanh toán + bán ghế + phát hành vé + ghi outbox" là MỘT transaction duy nhất.
 */
@Slf4j
@Service
public class BookingPaymentService {

    private final BookingRepository bookingRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final ShowtimeRepository showtimeRepository;
    private final TicketCodeSigner ticketCodeSigner;
    private final OutboxWriter outboxWriter;

    public BookingPaymentService(BookingRepository bookingRepository,
                                 ShowtimeSeatRepository showtimeSeatRepository,
                                 ShowtimeRepository showtimeRepository,
                                 TicketCodeSigner ticketCodeSigner,
                                 OutboxWriter outboxWriter) {
        this.bookingRepository = bookingRepository;
        this.showtimeSeatRepository = showtimeSeatRepository;
        this.showtimeRepository = showtimeRepository;
        this.ticketCodeSigner = ticketCodeSigner;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public Optional<PayableBooking> findByCode(String bookingCode) {
        return bookingRepository.findByBookingCode(bookingCode).map(BookingPaymentService::toPayable);
    }

    @Transactional(readOnly = true)
    public Optional<PayableBooking> findById(Long bookingId) {
        return bookingRepository.findById(bookingId).map(BookingPaymentService::toPayable);
    }

    /**
     * Ghi nhận đơn đã được thanh toán.
     *
     * <pre>
     * Khóa đơn (FOR UPDATE)
     *  ├─ CONFIRMED                       → ALREADY_CONFIRMED (giao dịch khác đã trả cho đơn này)
     *  ├─ REFUND_PENDING / REFUNDED       → BOOKING_CLOSED
     *  └─ PENDING_PAYMENT / EXPIRED / CANCELLED
     *       Khóa các ghế của đơn (FOR UPDATE, theo thứ tự id)
     *        ├─ mọi ghế là HELD-của-đơn-này hoặc AVAILABLE → SOLD, phát hành vé, outbox BOOKING_CONFIRMED
     *        │      (đơn EXPIRED/CANCELLED mà ghế còn trống: "gia hạn" và xác nhận → CONFIRMED_LATE)
     *        └─ có ghế đã thuộc về người khác  → đơn REFUND_PENDING → SEATS_UNAVAILABLE
     * </pre>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConfirmResult confirmPaid(Long bookingId, Instant paidAt) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOOKING_NOT_FOUND));

        switch (booking.getStatus()) {
            case CONFIRMED -> {
                return ConfirmResult.ALREADY_CONFIRMED;
            }
            case REFUND_PENDING, REFUNDED -> {
                return ConfirmResult.BOOKING_CLOSED;
            }
            default -> {
                // PENDING_PAYMENT, EXPIRED, CANCELLED: xử lý tiếp bên dưới
            }
        }

        boolean late = booking.getStatus() != BookingStatus.PENDING_PAYMENT;
        List<Long> seatIds = booking.showtimeSeatIds().stream().sorted().toList();
        List<ShowtimeSeat> seats = showtimeSeatRepository.findAllByIdForUpdate(seatIds);
        boolean allObtainable = seats.size() == seatIds.size()
                && seats.stream().allMatch(seat -> isObtainableBy(seat, booking.getId()));

        if (!allObtainable) {
            // Phòng hờ đơn PENDING còn giữ một phần ghế: trả lại, nếu không các ghế này kẹt HELD mãi
            // (job hết hạn chỉ quét đơn PENDING_PAYMENT)
            showtimeSeatRepository.releaseHeldSeats(booking.getId());
            booking.markRefundPending();
            log.warn("Đơn {} ({}) đã thanh toán nhưng ghế đã thuộc về người khác → chờ hoàn tiền",
                    booking.getBookingCode(), booking.getStatus());
            return ConfirmResult.SEATS_UNAVAILABLE;
        }

        int sold = showtimeSeatRepository.sellSeats(seatIds, booking.getId());
        if (sold != seatIds.size()) {
            // Không thể xảy ra vì các dòng đã bị khóa và kiểm tra ở trên; nếu xảy ra thì rollback toàn bộ
            throw new IllegalStateException("Bán ghế cho đơn " + booking.getBookingCode() + ": cần " + seatIds.size()
                    + ", được " + sold);
        }
        booking.confirm(paidAt);
        issueTickets(booking);
        bookingRepository.flush();
        publishConfirmed(booking);

        log.info("Đơn {} đã thanh toán, bán {} ghế{}", booking.getBookingCode(), sold,
                late ? " (thanh toán sau khi hết hạn giữ chỗ, ghế vẫn còn trống)" : "");
        return late ? ConfirmResult.CONFIRMED_LATE : ConfirmResult.CONFIRMED;
    }

    /** Cổng thanh toán đã nhận yêu cầu hoàn tiền: đơn REFUND_PENDING → REFUNDED (trạng thái khác: bỏ qua). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markRefunded(Long bookingId) {
        bookingRepository.findByIdForUpdate(bookingId)
                .filter(b -> b.getStatus() == BookingStatus.REFUND_PENDING)
                .ifPresent(Booking::markRefunded);
    }

    private static boolean isObtainableBy(ShowtimeSeat seat, Long bookingId) {
        return seat.getStatus() == SeatStatus.AVAILABLE
                || (seat.getStatus() == SeatStatus.HELD && bookingId.equals(seat.getBookingId()));
    }

    private void issueTickets(Booking booking) {
        for (BookingItem item : booking.getItems()) {
            // getShowtimeSeat().getId() trên proxy LAZY không phát sinh truy vấn
            item.issueTicket(ticketCodeSigner.sign(new TicketClaims(
                    item.getId(), booking.getShowtimeId(), item.getShowtimeSeat().getId())));
        }
    }

    private void publishConfirmed(Booking booking) {
        Showtime showtime = showtimeRepository.findWithDetailsById(booking.getShowtimeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND));
        List<BookingConfirmedEvent.Ticket> tickets = bookingRepository.findBookedSeats(booking.getId()).stream()
                .map(BookingPaymentService::toTicket)
                .toList();
        var venue = showtime.getAuditorium().getVenue();
        BookingConfirmedEvent event = new BookingConfirmedEvent(
                booking.getBookingCode(),
                booking.getCustomerEmail(),
                showtime.getShow().getTitle(),
                showtime.getStartTime(),
                venue.getName(),
                venue.getAddress(),
                showtime.getAuditorium().getName(),
                tickets,
                booking.getTotalAmount(),
                booking.getConfirmedAt());
        outboxWriter.append(EventTypes.AGGREGATE_BOOKING, booking.getId(), EventTypes.BOOKING_CONFIRMED, event);
    }

    private static BookingConfirmedEvent.Ticket toTicket(BookedSeatView seat) {
        return new BookingConfirmedEvent.Ticket(seat.label(), seat.seatType().name(), seat.price(),
                seat.ticketCode());
    }

    private static PayableBooking toPayable(Booking booking) {
        return new PayableBooking(booking.getId(), booking.getBookingCode(), booking.getUserId(),
                booking.getCustomerEmail(), booking.getStatus(), booking.getTotalAmount(), booking.getExpiresAt());
    }
}
