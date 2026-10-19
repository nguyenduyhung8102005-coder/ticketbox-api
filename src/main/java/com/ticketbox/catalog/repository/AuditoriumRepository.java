package com.ticketbox.catalog.repository;

import com.ticketbox.catalog.domain.Auditorium;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuditoriumRepository extends JpaRepository<Auditorium, Long> {

    @Query("select a from Auditorium a join fetch a.venue where a.id = :id")
    Optional<Auditorium> findWithVenueById(@Param("id") Long id);

    @Query("select a from Auditorium a join fetch a.venue v where v.id = :venueId order by a.name")
    List<Auditorium> findByVenueId(@Param("venueId") Long venueId);
}
