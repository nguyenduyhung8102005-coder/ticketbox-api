package com.ticketbox.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketbox.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Luồng quản trị danh mục qua HTTP: địa điểm → phòng → sơ đồ ghế → show → suất chiếu → API công khai. */
class CatalogIT extends AbstractIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private String admin;
    private long auditoriumId;
    private long showId;
    private Instant start;

    @BeforeEach
    void setUpCatalog() {
        admin = adminToken();

        long venueId = post("/api/v1/admin/venues",
                Map.of("name", "CGV Vincom Bà Triệu", "city", "Hà Nội", "address", "191 Bà Triệu"))
                .at("/data/id").asLong();
        auditoriumId = post("/api/v1/admin/auditoriums", Map.of("venueId", venueId, "name", "Phòng 1"))
                .at("/data/id").asLong();

        // 2 hàng thường x 10 ghế + 1 hàng VIP x 10 ghế = 30 ghế
        JsonNode layout = post("/api/v1/admin/auditoriums/" + auditoriumId + "/seats", Map.of("rows", List.of(
                Map.of("rowLabel", "A", "seatCount", 10),
                Map.of("rowLabel", "B", "seatCount", 10),
                Map.of("rowLabel", "C", "seatCount", 10, "seatType", "VIP"))));
        assertThat(layout.at("/data/totalSeats").asInt()).isEqualTo(30);

        showId = post("/api/v1/admin/shows", Map.of("type", "MOVIE", "title", "Lật Mặt 8: Vòng Tay Nắng",
                "durationMin", 135, "status", "PUBLISHED")).at("/data/id").asLong();
        start = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
    }

    @Test
    void createShowtime_generatesOneShowtimeSeatPerSeat_inSameTransaction() {
        ResponseEntity<JsonNode> response = createShowtime(start);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        long showtimeId = response.getBody().at("/data/id").asLong();
        assertThat(response.getBody().at("/data/totalSeats").asInt()).isEqualTo(30);
        assertThat(response.getBody().at("/data/prices/VIP").asInt()).isEqualTo(120000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM showtime_seats WHERE showtime_id = ? AND status = 'AVAILABLE'",
                Integer.class, showtimeId)).isEqualTo(30);
    }

    @Test
    void createShowtime_overlappingInSameAuditorium_returns409AndLeavesNoTrace() {
        createShowtime(start);

        // Suất thứ hai bắt đầu khi suất đầu (135 phút) chưa kết thúc
        ResponseEntity<JsonNode> overlap = createShowtime(start.plus(Duration.ofMinutes(60)));

        assertThat(overlap.getStatusCode().value()).isEqualTo(409);
        assertThat(overlap.getBody().at("/error/code").asText()).isEqualTo("SHOWTIME_OVERLAP");
        // Transaction rollback: không có suất "mồ côi", không có ghế sinh dở
        assertThat(jdbc.queryForObject("SELECT count(*) FROM showtimes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM showtime_seats", Integer.class)).isEqualTo(30);
    }

    @Test
    void createShowtime_missingPriceForVipSeats_returns400() {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/admin/showtimes", Map.of(
                "showId", showId, "auditoriumId", auditoriumId, "startTime", start.toString(),
                "prices", Map.of("STANDARD", 90000)), admin);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("TICKET_PRICE_MISSING");
    }

    @Test
    void seatLayout_cannotBeCreatedTwice() {
        ResponseEntity<JsonNode> again = call(HttpMethod.POST, "/api/v1/admin/auditoriums/" + auditoriumId + "/seats",
                Map.of("rows", List.of(Map.of("rowLabel", "D", "seatCount", 5))), admin);

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().at("/error/code").asText()).isEqualTo("SEAT_LAYOUT_ALREADY_EXISTS");
    }

    @Test
    void publicApis_listShowsAndShowtimesByDateAndCity() {
        createShowtime(start);
        post("/api/v1/admin/shows", Map.of("type", "MOVIE", "title", "Phim Nháp"));   // DRAFT: khách không thấy

        ResponseEntity<JsonNode> list = call(HttpMethod.GET, "/api/v1/shows?type=MOVIE&page=0", null, null);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody().at("/data/totalItems").asInt()).isEqualTo(1);
        assertThat(list.getBody().at("/data/items/0/slug").asText()).isEqualTo("lat-mat-8-vong-tay-nang");

        ResponseEntity<JsonNode> detail = call(HttpMethod.GET, "/api/v1/shows/lat-mat-8-vong-tay-nang", null, null);
        assertThat(detail.getBody().at("/data/durationMin").asInt()).isEqualTo(135);

        ResponseEntity<JsonNode> draft = call(HttpMethod.GET, "/api/v1/shows/phim-nhap", null, null);
        assertThat(draft.getStatusCode().value()).isEqualTo(404);

        String date = LocalDate.ofInstant(start, VN).toString();
        ResponseEntity<JsonNode> inHanoi = call(HttpMethod.GET, "/api/v1/shows/{id}/showtimes?date={date}&city={city}",
                null, null, showId, date, "hà nội");
        assertThat(inHanoi.getBody().at("/data").size()).isEqualTo(1);
        assertThat(inHanoi.getBody().at("/data/0/venueName").asText()).isEqualTo("CGV Vincom Bà Triệu");

        ResponseEntity<JsonNode> inDanang = call(HttpMethod.GET, "/api/v1/shows/{id}/showtimes?date={date}&city={city}",
                null, null, showId, date, "Đà Nẵng");
        assertThat(inDanang.getBody().at("/data").size()).isZero();
    }

    @Test
    void deleteShow_withShowtimes_isRejected() {
        createShowtime(start);

        ResponseEntity<JsonNode> response = call(HttpMethod.DELETE, "/api/v1/admin/shows/" + showId, null, admin);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("SHOW_HAS_SHOWTIMES");
    }

    private ResponseEntity<JsonNode> createShowtime(Instant startTime) {
        return call(HttpMethod.POST, "/api/v1/admin/showtimes", Map.of(
                "showId", showId,
                "auditoriumId", auditoriumId,
                "startTime", startTime.toString(),
                "prices", Map.of("STANDARD", 90000, "VIP", 120000)), admin);
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, path, body, admin);
        assertThat(response.getStatusCode().value()).as("POST %s -> %s", path, response.getBody()).isEqualTo(201);
        return response.getBody();
    }
}
