package com.ticketbox.catalog;

import com.ticketbox.catalog.domain.AgeRating;
import com.ticketbox.catalog.domain.Auditorium;
import com.ticketbox.catalog.domain.Show;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.domain.Venue;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

/** Dựng nhanh entity catalog cho unit test (entity không có setter, id gán bằng reflection). */
public final class CatalogTestData {

    private CatalogTestData() {
    }

    public static Show show(Long id, ShowStatus status) {
        Show show = Show.create(new Show.ShowDetails(ShowType.MOVIE, "Phim Test", null, 120, AgeRating.T13,
                null, null, LocalDate.parse("2026-10-01"), status), "phim-test");
        return withId(show, id);
    }

    public static Auditorium auditorium(Long id) {
        Venue venue = withId(Venue.create("Rạp Test", "Hà Nội", "1 Tràng Tiền"), 1L);
        return withId(Auditorium.create(venue, "Phòng 1"), id);
    }

    public static Showtime showtime(Long id, Instant start, ShowtimeStatus status) {
        Showtime showtime = Showtime.create(show(1L, ShowStatus.PUBLISHED), auditorium(5L), start,
                start.plusSeconds(7200), status, null);
        return withId(showtime, id);
    }

    public static <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
