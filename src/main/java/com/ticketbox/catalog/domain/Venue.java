package com.ticketbox.catalog.domain;

import com.ticketbox.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Rạp / nhà hát / sân vận động. */
@Getter
@Entity
@Table(name = "venues")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Venue extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "address", nullable = false)
    private String address;

    public static Venue create(String name, String city, String address) {
        Venue venue = new Venue();
        venue.name = name;
        venue.city = city;
        venue.address = address;
        return venue;
    }
}
