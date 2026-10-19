package com.ticketbox.catalog.repository;

import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    /** Nạp kèm show, phòng, địa điểm trong 1 câu SQL (tránh N+1 khi dựng response). */
    @Query("""
            select st from Showtime st
              join fetch st.show
              join fetch st.auditorium a
              join fetch a.venue
             where st.id = :id
            """)
    Optional<Showtime> findWithDetailsById(@Param("id") Long id);

    @Query("""
            select st from Showtime st
              join fetch st.auditorium a
              join fetch a.venue v
             where st.show.id = :showId
               and st.startTime >= :from and st.startTime < :to
               and st.status in :statuses
             order by st.startTime
            """)
    List<Showtime> findByShowAndPeriod(@Param("showId") Long showId,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      @Param("statuses") Collection<ShowtimeStatus> statuses);

    @Query("""
            select st from Showtime st
              join fetch st.auditorium a
              join fetch a.venue v
             where st.show.id = :showId
               and st.startTime >= :from and st.startTime < :to
               and st.status in :statuses
               and lower(v.city) = lower(:city)
             order by st.startTime
            """)
    List<Showtime> findByShowAndPeriodAndCity(@Param("showId") Long showId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to,
                                             @Param("statuses") Collection<ShowtimeStatus> statuses,
                                             @Param("city") String city);

    boolean existsByShow_Id(Long showId);
}
