package com.ticketbox.support;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bóc nội dung email MIME để kiểm tra trong test: phần HTML và các ảnh inline (Content-ID). */
public record MailContent(String subject, List<String> recipients, String html, List<InlineImage> inlineImages) {

    public record InlineImage(String contentId, String contentType, byte[] bytes) {
    }

    public static MailContent of(MimeMessage message) {
        try {
            List<String> recipients = new ArrayList<>();
            for (var address : message.getAllRecipients()) {
                recipients.add(((InternetAddress) address).getAddress());
            }
            StringBuilder html = new StringBuilder();
            List<InlineImage> images = new ArrayList<>();
            collect(message, html, images);
            return new MailContent(message.getSubject(), recipients, html.toString(), images);
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được email", e);
        }
    }

    private static void collect(Part part, StringBuilder html, List<InlineImage> images) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), html, images);
            }
            return;
        }
        String[] contentId = part.getHeader("Content-ID");
        if (contentId != null && contentId.length > 0) {
            images.add(new InlineImage(contentId[0].replaceAll("[<>]", ""), part.getContentType(), readBytes(part)));
        } else if (part.isMimeType("text/html")) {
            html.append(content);
        }
    }

    private static byte[] readBytes(Part part) throws Exception {
        if (part instanceof BodyPart bodyPart) {
            try (var in = bodyPart.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return new byte[0];
    }
}
