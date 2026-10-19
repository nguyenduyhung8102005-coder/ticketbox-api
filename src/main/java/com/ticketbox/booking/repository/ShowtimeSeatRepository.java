package com.ticketbox.booking.repository;

import com.ticketbox.booking.domain.ShowtimeSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ShowtimeSeatRepository extends JpaRepository<ShowtimeSeat, Long> {

    /**
     * LỚP BẢO VỆ THỨ HAI (nguồn sự thật): giữ ghế bằng UPDATE có điều kiện.
     * <p>
     * PostgreSQL khóa từng dòng khi UPDATE; request đến sau phải chờ request trước commit, rồi
     * đánh giá lại điều kiện {@code status = AVAILABLE} trên dữ liệu MỚI NHẤT. Vì vậy với N request
     * tranh một ghế, đúng 1 request cập nhật được dòng đó; các request còn lại nhận số dòng = 0.
     * Không cần SELECT trước (tránh race "đọc thấy trống rồi mới ghi").
     *
     * @return số ghế giữ được; khác số ghế yêu cầu nghĩa là có ghế đã bị người khác lấy
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ShowtimeSeat s
               set s.status = com.ticketbox.booking.domain.SeatStatus.HELD,
                   s.bookingId = :bookingId,
                   s.heldUntil = :heldUntil,
                   s.version = s.version + 1
             where s.showtimeId = :showtimeId
               and s.id in :seatIds
               and s.status = com.ticketbox.booking.domain.SeatStatus.AVAILABLE
            """)
    int holdSeats(@Param("showtimeId") Long showtimeId,
                  @Param("seatIds") Collection<Long> seatIds,
                  @Param("bookingId") Long bookingId,
                  @Param("heldUntil") Instant heldUntil);

    /** Trả ghế đang giữ của một đơn về AVAILABLE (hủy đơn). Điều kiện booking_id để không nhả nhầm ghế của đơn khác. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ShowtimeSeat s
               set s.status = com.ticketbox.booking.domain.SeatStatus.AVAILABLE,
                   s.bookingId = null,
                   s.heldUntil = null,
                   s.version = s.version + 1
             where s.bookingId = :bookingId
               and s.status = com.ticketbox.booking.domain.SeatStatus.HELD
            """)
    int releaseHeldSeats(@Param("bookingId") Long bookingId);

    /** Như trên nhưng cho nhiều đơn một lúc (job hết hạn). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ShowtimeSeat s
               set s.status = com.ticketbox.booking.domain.SeatStatus.AVAILABLE,
                   s.bookingId = null,
                   s.heldUntil = null,
                   s.version = s.version + 1
             where s.bookingId in :bookingIds
               and s.status = com.ticketbox.booking.domain.SeatStatus.HELD
            """)
    int releaseHeldSeatsOfBookings(@Param("bookingIds") Collection<Long> bookingIds);

    /** Thông tin ghế (nhãn, loại) của các ghế được yêu cầu, chỉ lấy ghế thuộc đúng suất chiếu. */
    @Query("""
            select new com.ticketbox.booking.repository.SeatView(
                       ss.id, s.rowLabel, s.seatNumber, s.seatType, ss.status)
              from ShowtimeSeat ss join ss.seat s
             where ss.showtimeId = :showtimeId
               and ss.id in :seatIds
            """)
    List<SeatView> findSeatViews(@Param("showtimeId") Long showtimeId, @Param("seatIds") Collection<Long> seatIds);

    /** Toàn bộ sơ đồ ghế kèm trạng thái của một suất. */
    @Query("""
            select new com.ticketbox.booking.repository.SeatView(
                       ss.id, s.rowLabel, s.seatNumber, s.seatType, ss.status)
              from ShowtimeSeat ss join ss.seat s
             where ss.showtimeId = :showtimeId
             order by s.rowLabel, s.seatNumber
            """)
    List<SeatView> findSeatMap(@Param("showtimeId") Long showtimeId);
}
