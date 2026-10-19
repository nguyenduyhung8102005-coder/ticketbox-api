package com.ticketbox.booking.domain;

import com.ticketbox.catalog.domain.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Trạng thái một ghế trong một suất chiếu. Mọi thay đổi trạng thái đi qua UPDATE có điều kiện
 * trong {@code ShowtimeSeatRepository}, không sửa qua entity, nên lớp này không có setter.
 */
@Getter
@Entity
@Table(name = "showtime_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShowtimeSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "showtime_id", nullable = false)
    private Long showtimeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SeatStatus status;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "held_until")
    private Instant heldUntil;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
