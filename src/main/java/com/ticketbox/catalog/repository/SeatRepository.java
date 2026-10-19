package com.ticketbox.catalog.repository;

import com.ticketbox.catalog.domain.Seat;
import com.ticketbox.catalog.domain.SeatType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByAuditoriumIdOrderByRowLabelAscSeatNumberAsc(Long auditoriumId);

    boolean existsByAuditoriumId(Long auditoriumId);

    /** Các loại ghế đang dùng trong phòng: suất chiếu phải có giá cho đủ các loại này. */
    @Query("select distinct s.seatType from Seat s where s.auditoriumId = :auditoriumId and s.active = true")
    List<SeatType> findActiveSeatTypes(@Param("auditoriumId") Long auditoriumId);
}
