package com.ticketbox.catalog.repository;

import com.ticketbox.catalog.domain.TicketPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketPriceRepository extends JpaRepository<TicketPrice, TicketPrice.Key> {

    List<TicketPrice> findByShowtimeId(Long showtimeId);
}
