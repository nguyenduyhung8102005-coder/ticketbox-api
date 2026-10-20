package com.ticketbox.notification.email;

import com.ticketbox.common.events.BookingConfirmedEvent;
import com.ticketbox.common.events.PaymentRefundRequestedEvent;
import com.ticketbox.common.events.PaymentRefundedEvent;
import com.ticketbox.notification.qr.QrCodeGenerator;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Soạn và gửi email HTML (Thymeleaf). Mã QR được đính kèm dạng ảnh INLINE ({@code cid:}) thay vì data URI,
 * vì Gmail/Outlook chặn ảnh base64 nhúng trong HTML.
 */
@Slf4j
@Component
public class TicketMailer {

    private static final Locale VI = Locale.forLanguageTag("vi-VN");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm, EEEE dd/MM/yyyy", VI);
    private static final Map<String, String> REFUND_REASONS = Map.of(
            "SEATS_UNAVAILABLE", "Thời gian giữ ghế đã hết trước khi thanh toán hoàn tất và ghế đã được người khác mua.",
            "DUPLICATE_PAYMENT", "Đơn của bạn đã được thanh toán bởi một giao dịch khác (thanh toán hai lần).",
            "BOOKING_CLOSED", "Đơn đặt vé đã được đóng trước khi giao dịch này hoàn tất.");

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final QrCodeGenerator qrCodeGenerator;
    private final MailProperties properties;
    private final ZoneId zone;

    public TicketMailer(JavaMailSender mailSender, TemplateEngine templateEngine, QrCodeGenerator qrCodeGenerator,
                        MailProperties properties) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.qrCodeGenerator = qrCodeGenerator;
        this.properties = properties;
        this.zone = ZoneId.of(properties.timezone());
    }

    public void sendBookingConfirmed(BookingConfirmedEvent event) {
        List<TicketLine> tickets = new ArrayList<>();
        for (int i = 0; i < event.tickets().size(); i++) {
            BookingConfirmedEvent.Ticket t = event.tickets().get(i);
            tickets.add(new TicketLine("qr-" + i, t.seatLabel(), t.seatType(), money(t.price()), t.ticketCode()));
        }
        Context context = new Context(VI);
        context.setVariable("bookingCode", event.bookingCode());
        context.setVariable("showTitle", event.showTitle());
        context.setVariable("startTime", time(event.startTime()));
        context.setVariable("venueName", event.venueName());
        context.setVariable("venueAddress", event.venueAddress());
        context.setVariable("auditoriumName", event.auditoriumName());
        context.setVariable("tickets", tickets);
        context.setVariable("totalAmount", money(event.totalAmount()));
        String html = templateEngine.process("email/booking-confirmed", context);

        send(event.customerEmail(), "[Ticketbox] Vé " + event.showTitle() + " - đơn " + event.bookingCode(), html,
                helper -> {
                    for (int i = 0; i < tickets.size(); i++) {
                        byte[] png = qrCodeGenerator.png(event.tickets().get(i).ticketCode());
                        helper.addInline(tickets.get(i).cid(), new ByteArrayResource(png), "image/png");
                    }
                });
        log.info("Đã gửi email vé đơn {} tới {}", event.bookingCode(), event.customerEmail());
    }

    public void sendRefundRequested(PaymentRefundRequestedEvent event) {
        sendRefund(event.customerEmail(), event.bookingCode(), event.amount(), event.reason(), false);
    }

    public void sendRefunded(PaymentRefundedEvent event) {
        sendRefund(event.customerEmail(), event.bookingCode(), event.amount(), event.reason(), true);
    }

    private void sendRefund(String to, String bookingCode, BigDecimal amount, String reason, boolean completed) {
        Context context = new Context(VI);
        context.setVariable("bookingCode", bookingCode);
        context.setVariable("amount", money(amount));
        context.setVariable("reasonText", REFUND_REASONS.getOrDefault(reason == null ? "" : reason,
                "Đơn đặt vé không thể hoàn tất."));
        context.setVariable("completed", completed);
        String html = templateEngine.process("email/payment-refund", context);
        String subject = completed
                ? "[Ticketbox] Đã hoàn tiền đơn " + bookingCode
                : "[Ticketbox] Xin lỗi, đơn " + bookingCode + " không thể hoàn tất - chúng tôi đang hoàn tiền";
        send(to, subject, html, helper -> {
        });
        log.info("Đã gửi email hoàn tiền ({}) đơn {} tới {}", completed ? "hoàn tất" : "đang xử lý", bookingCode, to);
    }

    private void send(String to, String subject, String html, InlineAttacher attacher) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from(), properties.fromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);           // addInline phải gọi SAU setText
            attacher.attach(helper);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Không soạn được email tới " + to, e);
        }
        mailSender.send(message);
    }

    private String time(Instant instant) {
        return instant == null ? "" : TIME_FORMAT.format(instant.atZone(zone));
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "" : NumberFormat.getNumberInstance(VI).format(amount) + " đ";
    }

    @FunctionalInterface
    private interface InlineAttacher {
        void attach(MimeMessageHelper helper) throws MessagingException;
    }

    /** Một dòng vé trong email. {@code cid} khớp với ảnh QR đính kèm inline. */
    public record TicketLine(String cid, String seatLabel, String seatType, String price, String ticketCode) {
    }
}
