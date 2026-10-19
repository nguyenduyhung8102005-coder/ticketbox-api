package com.ticketbox.booking.domain;

import com.ticketbox.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "bookings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking extends BaseEntity {

    @Column(name = "booking_code", nullable = false)
    private String bookingCode;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "showtime_id", nullable = false)
    private Long showtimeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BookingStatus status;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingItem> items = new ArrayList<>();

    public static Booking pending(String bookingCode, Long userId, String customerEmail, Long showtimeId,
                                  BigDecimal totalAmount, Instant expiresAt, String idempotencyKey) {
        Booking booking = new Booking();
        booking.bookingCode = bookingCode;
        booking.userId = userId;
        booking.customerEmail = customerEmail;
        booking.showtimeId = showtimeId;
        booking.status = BookingStatus.PENDING_PAYMENT;
        booking.totalAmount = totalAmount;
        booking.expiresAt = expiresAt;
        booking.idempotencyKey = idempotencyKey;
        return booking;
    }

    public void addItem(ShowtimeSeat showtimeSeat, BigDecimal price) {
        items.add(new BookingItem(this, showtimeSeat, price));
    }

    public boolean isPendingPayment() {
        return status == BookingStatus.PENDING_PAYMENT;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId != null && this.userId.equals(userId);
    }

    public void cancel() {
        requirePending();
        this.status = BookingStatus.CANCELLED;
    }

    public void expire() {
        requirePending();
        this.status = BookingStatus.EXPIRED;
    }

    public List<Long> showtimeSeatIds() {
        return items.stream().map(item -> item.getShowtimeSeat().getId()).toList();
    }

    private void requirePending() {
        if (status != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Đơn " + bookingCode + " không còn ở trạng thái PENDING_PAYMENT");
        }
    }
}
