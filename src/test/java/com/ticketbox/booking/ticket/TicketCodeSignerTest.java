package com.ticketbox.booking.ticket;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketCodeSignerTest {

    private final TicketCodeSigner signer =
            new TicketCodeSigner(new TicketProperties("test-only-ticket-secret-0123456789abcdef"));

    @Test
    void signedCode_verifiesBackToSameClaims() {
        TicketClaims claims = new TicketClaims(123, 45, 678);

        String code = signer.sign(claims);

        assertThat(code).startsWith("TBK1.").hasSizeLessThanOrEqualTo(128);   // vừa cột ticket_code
        assertThat(signer.verify(code)).contains(claims);
    }

    @Test
    void changingSeatInsideCode_isDetected() {
        String code = signer.sign(new TicketClaims(123, 45, 678));
        String[] parts = code.split("\\.");
        String forgedBody = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("123.45.679".getBytes(StandardCharsets.UTF_8));

        assertThat(signer.verify(parts[0] + "." + forgedBody + "." + parts[2])).isEmpty();
    }

    @Test
    void codeSignedWithAnotherSecret_isRejected() {
        String code = new TicketCodeSigner(new TicketProperties("another-secret-0123456789abcdef-xyz"))
                .sign(new TicketClaims(1, 2, 3));

        assertThat(signer.verify(code)).isEmpty();
    }

    @Test
    void garbage_isRejectedWithoutException() {
        assertThat(signer.verify(null)).isEmpty();
        assertThat(signer.verify("")).isEmpty();
        assertThat(signer.verify("TBK1.@@@.###")).isEmpty();
        assertThat(signer.verify("TBK9.MQ.AA")).isEmpty();
    }

    @Test
    void shortSecret_isRejectedAtStartup() {
        assertThatThrownBy(() -> new TicketCodeSigner(new TicketProperties("short")))
                .isInstanceOf(IllegalStateException.class);
    }
}
