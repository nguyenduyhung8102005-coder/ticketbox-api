package com.ticketbox.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketbox.AbstractIntegrationTest;
import com.ticketbox.booking.dto.BookingDtos.HoldSeatsCommand;
import com.ticketbox.booking.service.BookingExpiryService;
import com.ticketbox.booking.service.BookingService;
import com.ticketbox.booking.service.SeatHoldCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Vòng đời đơn qua HTTP: xem sơ đồ ghế → giữ ghế → xem đơn → hủy / hết hạn. */
class BookingFlowIT extends AbstractIntegrationTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingExpiryService expiryService;

    private ShowtimeFixture fixture;
    private long a1;
    private long a2;
    private String alice;

    @BeforeEach
    void setUpShowtime() {
        fixture = createOnSaleShowtime(2, 5);   // hàng A, B x 5 ghế = 10 ghế
        a1 = seatId(fixture.showtimeId(), "A", 1);
        a2 = seatId(fixture.showtimeId(), "A", 2);
        alice = customerToken("alice@test.vn");
    }

    @Test
    void seatMap_isPublic_andShowsAllSeatsAvailable() {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET,
                "/api/v1/showtimes/" + fixture.showtimeId() + "/seats", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().at("/data/availableCount").asInt()).isEqualTo(10);
        assertThat(response.getBody().at("/data/seats/0/label").asText()).isEqualTo("A1");
        assertThat(response.getBody().at("/data/seats/0/price").asInt()).isEqualTo(90000);
    }

    @Test
    void hold_createsPendingBooking_marksSeatsHeld_andSetsRedisKeys() {
        ResponseEntity<JsonNode> response = hold(alice, "alice-key-0001", a1, a2);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode data = response.getBody().get("data");
        assertThat(data.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(data.get("totalAmount").asInt()).isEqualTo(180000);
        assertThat(data.get("seats")).hasSize(2);
        assertThat(data.get("bookingCode").asText()).startsWith("TB");

        assertThat(seatStatus(a1)).isEqualTo("HELD");
        assertThat(seatStatus(a2)).isEqualTo("HELD");
        assertThat(redis.opsForValue().get(SeatHoldCache.key(fixture.showtimeId(), a1)))
                .isEqualTo(data.get("bookingCode").asText());
        assertThat(redis.getExpire(SeatHoldCache.key(fixture.showtimeId(), a1))).isPositive();
    }

    @Test
    void hold_sameIdempotencyKey_returnsSameBookingInsteadOfCreatingAnother() {
        String first = hold(alice, "alice-key-0001", a1).getBody().at("/data/bookingCode").asText();
        ResponseEntity<JsonNode> retry = hold(alice, "alice-key-0001", a1);

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getBody().at("/data/bookingCode").asText()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(1);
    }

    @Test
    void hold_withoutIdempotencyKey_returns400() {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/v1/bookings",
                Map.of("showtimeId", fixture.showtimeId(), "showtimeSeatIds", List.of(a1)), alice);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void hold_withoutLogin_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "anonymous-0001");
        ResponseEntity<JsonNode> response = callWithHeaders(HttpMethod.POST, "/api/v1/bookings",
                Map.of("showtimeId", fixture.showtimeId(), "showtimeSeatIds", List.of(a1)), null, headers);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void hold_seatAlreadyHeldByAnotherUser_returns409() {
        String bob = customerToken("bob@test.vn");
        hold(alice, "alice-key-0001", a1);

        ResponseEntity<JsonNode> response = hold(bob, "bob-key-00001", a1);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("SEAT_ALREADY_TAKEN");
    }

    @Test
    void getBooking_onlyOwnerCanSeeIt() {
        String bob = customerToken("bob@test.vn");
        String code = hold(alice, "alice-key-0001", a1).getBody().at("/data/bookingCode").asText();

        assertThat(call(HttpMethod.GET, "/api/v1/bookings/" + code, null, alice).getStatusCode().value())
                .isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/bookings/" + code, null, bob).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void cancel_releasesSeatsInDatabaseAndRedis_soOthersCanBookThem() {
        String code = hold(alice, "alice-key-0001", a1, a2).getBody().at("/data/bookingCode").asText();

        ResponseEntity<JsonNode> cancelled = call(HttpMethod.DELETE, "/api/v1/bookings/" + code, null, alice);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
        assertThat(redis.hasKey(SeatHoldCache.key(fixture.showtimeId(), a1))).isFalse();

        // Hủy lần 2: đơn không còn ở trạng thái chờ thanh toán
        ResponseEntity<JsonNode> again = call(HttpMethod.DELETE, "/api/v1/bookings/" + code, null, alice);
        assertThat(again.getBody().at("/error/code").asText()).isEqualTo("BOOKING_NOT_CANCELLABLE");

        // Người khác giữ được ghế vừa nhả
        String bob = customerToken("bob@test.vn");
        assertThat(hold(bob, "bob-key-00001", a1).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void myBookings_listsOnlyMyBookings() {
        String bob = customerToken("bob@test.vn");
        hold(alice, "alice-key-0001", a1);
        hold(bob, "bob-key-00001", a2);

        ResponseEntity<JsonNode> mine = call(HttpMethod.GET, "/api/v1/me/bookings", null, alice);

        assertThat(mine.getBody().at("/data/totalItems").asInt()).isEqualTo(1);
        assertThat(mine.getBody().at("/data/items/0/showTitle").asText()).isEqualTo("Phim Hot");
    }

    @Test
    void expiryJob_expiresOverdueBookings_andReturnsSeats() {
        long userId = createUser("carol@test.vn", com.ticketbox.user.domain.Role.CUSTOMER);
        var booking = bookingService.holdSeats(new HoldSeatsCommand(userId, "carol@test.vn",
                fixture.showtimeId(), List.of(a1, a2), "carol-key-0001"));
        // Giả lập 10 phút đã trôi qua
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE booking_code = ?",
                booking.bookingCode());

        int expired = expiryService.expireDueBookings();

        assertThat(expired).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE booking_code = ?", String.class,
                booking.bookingCode())).isEqualTo("EXPIRED");
        assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
        assertThat(seatStatus(a2)).isEqualTo("AVAILABLE");
        assertThat(redis.hasKey(SeatHoldCache.key(fixture.showtimeId(), a1))).isFalse();
        // Chạy lại không làm gì thêm (idempotent)
        assertThat(expiryService.expireDueBookings()).isZero();
    }

    @Test
    void expiryJob_doesNotTouchBookingsStillWithinHoldTime() {
        hold(alice, "alice-key-0001", a1);

        assertThat(expiryService.expireDueBookings()).isZero();
        assertThat(seatStatus(a1)).isEqualTo("HELD");
    }

    private ResponseEntity<JsonNode> hold(String token, String idempotencyKey, Long... seatIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return callWithHeaders(HttpMethod.POST, "/api/v1/bookings",
                Map.of("showtimeId", fixture.showtimeId(), "showtimeSeatIds", List.of(seatIds)), token, headers);
    }
}
