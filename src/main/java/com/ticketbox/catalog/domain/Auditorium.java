package com.ticketbox.catalog.domain;

import com.ticketbox.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Phòng chiếu / khu vực trong một địa điểm. */
@Getter
@Entity
@Table(name = "auditoriums")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auditorium extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "name", nullable = false)
    private String name;

    public static Auditorium create(Venue venue, String name) {
        Auditorium auditorium = new Auditorium();
        auditorium.venue = venue;
        auditorium.name = name;
        return auditorium;
    }
}
