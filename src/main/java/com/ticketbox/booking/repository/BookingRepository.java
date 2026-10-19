package com.ticketbox.booking.repository;

import com.ticketbox.booking.domain.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    Optional<Booking> findByBookingCode(String bookingCode);

    /**
     * Khóa dòng đơn hàng (SELECT ... FOR UPDATE) khi hủy: không chạy song song với job hết hạn
     * hay (Giai đoạn 3) xử lý IPN thanh toán trên cùng một đơn.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.bookingCode = :code")
    Optional<Booking> findByCodeForUpdate(@Param("code") String code);

    @Query("select distinct b from Booking b left join fetch b.items where b.id in :ids")
    List<Booking> findAllWithItemsByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * Lấy và KHÓA các đơn quá hạn giữ chỗ. {@code SKIP LOCKED}: dòng đang bị instance khác (hoặc
     * request hủy đơn) khóa thì bỏ qua thay vì chờ, nên chạy nhiều instance ứng dụng cùng lúc
     * vẫn không xử lý trùng một đơn, cũng không phải xếp hàng chờ nhau.
     */
    @Query(value = """
            SELECT id FROM bookings
             WHERE status = 'PENDING_PAYMENT' AND expires_at < :now
             ORDER BY expires_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockExpiredPendingIds(@Param("now") Instant now, @Param("limit") int limit);

    /** Ghế của đơn lấy theo booking_items (vẫn đúng sau khi đơn hủy/hết hạn và ghế đã được trả). */
    @Query("""
            select new com.ticketbox.booking.repository.BookedSeatView(
                       ss.id, s.rowLabel, s.seatNumber, s.seatType, i.price)
              from BookingItem i join i.showtimeSeat ss join ss.seat s
             where i.booking.id = :bookingId
             order by s.rowLabel, s.seatNumber
            """)
    List<BookedSeatView> findBookedSeats(@Param("bookingId") Long bookingId);

    @Query(value = """
            select new com.ticketbox.booking.repository.BookingSummaryView(
                       b.bookingCode, b.status, b.totalAmount, b.expiresAt, b.createdAt,
                       st.id, sh.title, st.startTime)
              from Booking b, Showtime st join st.show sh
             where st.id = b.showtimeId
               and b.userId = :userId
             order by b.createdAt desc
            """,
            countQuery = "select count(b) from Booking b where b.userId = :userId")
    Page<BookingSummaryView> findSummariesByUserId(@Param("userId") Long userId, Pageable pageable);
}
