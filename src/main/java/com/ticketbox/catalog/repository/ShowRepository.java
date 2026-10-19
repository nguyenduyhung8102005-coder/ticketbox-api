package com.ticketbox.catalog.repository;

import com.ticketbox.catalog.domain.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long>, JpaSpecificationExecutor<Show> {

    Optional<Show> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
