package com.ticketbox.booking.ticket;

import com.ticketbox.booking.domain.BookingStatus;
import com.ticketbox.booking.repository.BookingRepository;
import com.ticketbox.booking.repository.TicketView;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Soát vé. Hai lớp kiểm tra:
 * <ol>
 *   <li>Chữ ký HMAC (offline, không cần DB): chặn vé giả / vé bị sửa nội dung.</li>
 *   <li>Đối chiếu DB (online): vé có tồn tại, đơn còn CONFIRMED (chưa hoàn tiền).</li>
 * </ol>
 * Chưa có chức năng check-in (đánh dấu vé đã dùng) để chặn một vé quét 2 lần: thuộc phạm vi mở rộng.
 */
@Service
public class TicketVerificationService {

    private final TicketCodeSigner signer;
    private final BookingRepository bookingRepository;
    private final ShowtimeRepository showtimeRepository;

    public TicketVerificationService(TicketCodeSigner signer, BookingRepository bookingRepository,
                                     ShowtimeRepository showtimeRepository) {
        this.signer = signer;
        this.bookingRepository = bookingRepository;
        this.showtimeRepository = showtimeRepository;
    }

    @Transactional(readOnly = true)
    public TicketVerifyResponse verify(String ticketCode) {
        String code = ticketCode == null ? "" : ticketCode.trim();
        if (signer.verify(code).isEmpty()) {
            return TicketVerifyResponse.invalid("SIGNATURE_INVALID", "Mã vé không hợp lệ hoặc đã bị chỉnh sửa");
        }
        Optional<TicketView> found = bookingRepository.findTicket(code);
        if (found.isEmpty()) {
            return TicketVerifyResponse.invalid("NOT_ISSUED", "Chữ ký hợp lệ nhưng hệ thống không có vé này");
        }
        TicketView ticket = found.get();
        if (ticket.bookingStatus() != BookingStatus.CONFIRMED) {
            return TicketVerifyResponse.invalid("BOOKING_NOT_CONFIRMED",
                    "Đơn " + ticket.bookingCode() + " đang ở trạng thái " + ticket.bookingStatus());
        }
        Showtime showtime = showtimeRepository.findWithDetailsById(ticket.showtimeId()).orElseThrow();
        return new TicketVerifyResponse(true, "VALID", "Vé hợp lệ", ticket.bookingCode(),
                showtime.getShow().getTitle(), showtime.getStartTime(),
                showtime.getAuditorium().getVenue().getName(), showtime.getAuditorium().getName(),
                ticket.seatLabel());
    }

    public record TicketVerifyResponse(
            boolean valid,
            @Schema(example = "VALID", description = "VALID | SIGNATURE_INVALID | NOT_ISSUED | BOOKING_NOT_CONFIRMED")
            String result,
            String message,
            String bookingCode,
            String showTitle,
            Instant startTime,
            String venueName,
            String auditoriumName,
            String seatLabel) {

        static TicketVerifyResponse invalid(String result, String message) {
            return new TicketVerifyResponse(false, result, message, null, null, null, null, null, null);
        }
    }
}
