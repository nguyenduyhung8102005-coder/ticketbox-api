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

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

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

    /**
     * Đã thanh toán. Ngoài trường hợp bình thường (PENDING_PAYMENT), cho phép xác nhận đơn đã EXPIRED/CANCELLED:
     * khách trả tiền đúng lúc hết hạn giữ chỗ mà ghế vẫn còn trống thì vẫn giao vé (xem BookingPaymentService).
     */
    public void confirm(Instant at) {
        if (!isAwaitingOrLostHold()) {
            throw new IllegalStateException("Không thể xác nhận đơn " + bookingCode + " ở trạng thái " + status);
        }
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = at;
    }

    /** Đã thu tiền nhưng không giao được vé: chờ hoàn tiền. */
    public void markRefundPending() {
        if (!isAwaitingOrLostHold()) {
            throw new IllegalStateException("Không thể chuyển đơn " + bookingCode + " từ " + status
                    + " sang REFUND_PENDING");
        }
        this.status = BookingStatus.REFUND_PENDING;
    }

    public void markRefunded() {
        if (status != BookingStatus.REFUND_PENDING) {
            throw new IllegalStateException("Đơn " + bookingCode + " không ở trạng thái REFUND_PENDING");
        }
        this.status = BookingStatus.REFUNDED;
    }

    /** Chờ thanh toán, hoặc đã mất giữ chỗ (hết hạn/hủy) nhưng chưa từng được thanh toán. */
    private boolean isAwaitingOrLostHold() {
        return status == BookingStatus.PENDING_PAYMENT
                || status == BookingStatus.EXPIRED
                || status == BookingStatus.CANCELLED;
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
