package com.ticketbox.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Ghế vật lý, cố định theo phòng. Bảng seats không có created_at/updated_at nên không kế thừa BaseEntity.
 * Ghế được tạo hàng loạt bằng JDBC batch (xem SeatLayoutService), entity này chủ yếu để đọc.
 */
@Getter
@Entity
@Table(name = "seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auditorium_id", nullable = false)
    private Long auditoriumId;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatType seatType;

    @Column(name = "active", nullable = false)
    private boolean active;

    public String label() {
        return rowLabel + seatNumber;
    }
}
