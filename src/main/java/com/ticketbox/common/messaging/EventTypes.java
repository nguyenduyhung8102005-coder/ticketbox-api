package com.ticketbox.common.messaging;

/**
 * Tên các loại sự kiện và aggregate. Đây là "hợp đồng" giữa module phát và module nhận,
 * nên không đổi tên một loại sự kiện đã phát hành (consumer cũ sẽ bỏ qua tin nhắn).
 * Nội dung payload tương ứng nằm trong package {@code com.ticketbox.common.events}.
 */
public final class EventTypes {

    public static final String AGGREGATE_BOOKING = "BOOKING";
    public static final String AGGREGATE_PAYMENT = "PAYMENT";

    /** Đơn đã thanh toán, ghế đã bán, vé đã sinh. Payload: BookingConfirmedEvent. */
    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";

    /** Đã thu tiền nhưng không giao được vé, cần hoàn tiền. Payload: PaymentRefundRequestedEvent. */
    public static final String PAYMENT_REFUND_REQUESTED = "PAYMENT_REFUND_REQUESTED";

    /** Cổng thanh toán đã nhận yêu cầu hoàn tiền. Payload: PaymentRefundedEvent. */
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";

    private EventTypes() {
    }
}
