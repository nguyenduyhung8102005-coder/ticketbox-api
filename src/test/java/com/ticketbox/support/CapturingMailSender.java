package com.ticketbox.support;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mail sender giả cho integration test: giữ email trong bộ nhớ thay vì gửi SMTP, và có thể giả lập
 * SMTP lỗi N lần liên tiếp để kiểm tra cơ chế retry / Dead Letter Topic của consumer.
 */
public class CapturingMailSender extends JavaMailSenderImpl {

    private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicInteger attempts = new AtomicInteger();

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) throws MailException {
        attempts.incrementAndGet();
        if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new MailSendException("SMTP giả lập lỗi");
        }
        for (MimeMessage message : mimeMessages) {
            try {
                message.saveChanges();
            } catch (MessagingException e) {
                throw new MailSendException("Không lưu được email", e);
            }
            sent.add(message);
        }
    }

    /** Các lần gửi tiếp theo ném lỗi, tổng cộng {@code times} lần. */
    public void failNext(int times) {
        failuresLeft.set(times);
    }

    public int attempts() {
        return attempts.get();
    }

    public List<MimeMessage> sent() {
        return List.copyOf(sent);
    }

    /** Email có tiêu đề chứa đoạn text (thường là mã đơn, ngẫu nhiên nên không lẫn với test khác). */
    public List<MimeMessage> withSubjectContaining(String text) {
        return sent.stream().filter(m -> {
            try {
                return m.getSubject() != null && m.getSubject().contains(text);
            } catch (MessagingException e) {
                return false;
            }
        }).toList();
    }

    public void reset() {
        sent.clear();
        failuresLeft.set(0);
        attempts.set(0);
    }
}
