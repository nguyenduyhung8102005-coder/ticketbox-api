package com.ticketbox.catalog.repository;

import com.ticketbox.catalog.domain.Venue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    Page<Venue> findByCityIgnoreCase(String city, Pageable pageable);
}
