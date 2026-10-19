package com.ticketbox.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/** Giá vé theo loại ghế cho từng suất. Khóa chính kép (showtime_id, seat_type). */
@Getter
@Entity
@Table(name = "ticket_prices")
@IdClass(TicketPrice.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketPrice {

    @Id
    @Column(name = "showtime_id", nullable = false)
    private Long showtimeId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatType seatType;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    public static TicketPrice of(Long showtimeId, SeatType seatType, BigDecimal price) {
        TicketPrice ticketPrice = new TicketPrice();
        ticketPrice.showtimeId = showtimeId;
        ticketPrice.seatType = seatType;
        ticketPrice.price = price;
        return ticketPrice;
    }

    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long showtimeId;
        private SeatType seatType;
    }
}
