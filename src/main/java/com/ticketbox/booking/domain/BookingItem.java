package com.ticketbox.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Một ghế trong đơn, kèm giá chụp tại thời điểm đặt. ticket_code (nội dung QR) sinh khi đơn CONFIRMED. */
@Getter
@Entity
@Table(name = "booking_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_seat_id", nullable = false)
    private ShowtimeSeat showtimeSeat;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "ticket_code")
    private String ticketCode;

    BookingItem(Booking booking, ShowtimeSeat showtimeSeat, BigDecimal price) {
        this.booking = booking;
        this.showtimeSeat = showtimeSeat;
        this.price = price;
    }

    public void issueTicket(String ticketCode) {
        if (this.ticketCode != null) {
            throw new IllegalStateException("Ghế " + id + " đã được phát hành vé");
        }
        this.ticketCode = ticketCode;
    }
}
