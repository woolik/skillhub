package com.iflytek.skillhub.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class DongbuddySsoTicketTest {

    private static final String SECRET = "test-secret";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Test
    void verifiesFreshTicket() {
        long now = Instant.now().getEpochSecond();
        String ticket = sign("""
            {"phone":"+8619874719079","userId":"kb-4abbbd4a","nonce":"n1","iat":%d,"exp":%d}
            """.formatted(now - 5, now + 60).strip());

        var payload = DongbuddySsoTicket.verify(ticket, SECRET, Instant.now());

        assertThat(payload).isPresent();
        assertThat(payload.get().phone()).isEqualTo("+8619874719079");
        assertThat(payload.get().userId()).isEqualTo("kb-4abbbd4a");
        assertThat(payload.get().nonce()).isEqualTo("n1");
    }

    @Test
    void rejectsExpiredTicket() {
        long now = Instant.now().getEpochSecond();
        String ticket = sign("""
            {"phone":"+8619874719079","userId":"kb-4abbbd4a","nonce":"n2","iat":%d,"exp":%d}
            """.formatted(now - 120, now - 30).strip());

        assertThat(DongbuddySsoTicket.verify(ticket, SECRET, Instant.now())).isEmpty();
    }

    @Test
    void rejectsTamperedSignature() {
        long now = Instant.now().getEpochSecond();
        String ticket = sign("""
            {"phone":"+8619874719079","userId":"kb-4abbbd4a","nonce":"n3","iat":%d,"exp":%d}
            """.formatted(now - 5, now + 60).strip());
        String tampered = ticket.substring(0, ticket.length() - 2) + "xx";

        assertThat(DongbuddySsoTicket.verify(tampered, SECRET, Instant.now())).isEmpty();
    }

    @Test
    void rejectsWrongSecret() {
        long now = Instant.now().getEpochSecond();
        String ticket = sign("""
            {"phone":"+8619874719079","userId":"kb-4abbbd4a","nonce":"n4","iat":%d,"exp":%d}
            """.formatted(now - 5, now + 60).strip());

        assertThat(DongbuddySsoTicket.verify(ticket, "other-secret", Instant.now())).isEmpty();
    }

    @Test
    void rejectsMalformedTickets() {
        Instant now = Instant.now();
        assertThat(DongbuddySsoTicket.verify(null, SECRET, now)).isEmpty();
        assertThat(DongbuddySsoTicket.verify("nodot", SECRET, now)).isEmpty();
        assertThat(DongbuddySsoTicket.verify("a.", SECRET, now)).isEmpty();
        assertThat(DongbuddySsoTicket.verify(".b", SECRET, now)).isEmpty();
        assertThat(DongbuddySsoTicket.verify("!!!.!!!", SECRET, now)).isEmpty();
        assertThat(DongbuddySsoTicket.verify("a.b", "", now)).isEmpty();
    }

    @Test
    void rejectsPayloadMissingRequiredFields() {
        long now = Instant.now().getEpochSecond();
        String ticket = sign("""
            {"phone":"+8619874719079","nonce":"n5","iat":%d,"exp":%d}
            """.formatted(now - 5, now + 60).strip());

        assertThat(DongbuddySsoTicket.verify(ticket, SECRET, Instant.now())).isEmpty();
    }

    private static String sign(String payloadJson) {
        String payloadSegment = ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8));
            return payloadSegment + "." + ENCODER.encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
