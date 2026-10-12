package com.iflytek.skillhub.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;

class DongbuddyPassiveSessionAuthenticatorTest {

    private static final String SECRET = "test-secret";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private DongbuddySsoProperties properties;
    private DongbuddySsoProvisioningService provisioningService;
    private DongbuddyPassiveSessionAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        properties = new DongbuddySsoProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        provisioningService = mock(DongbuddySsoProvisioningService.class);
        authenticator = new DongbuddyPassiveSessionAuthenticator(properties, provisioningService);
    }

    @Test
    void emptyWhenProviderDisabled() {
        properties.setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DongbuddyPassiveSessionAuthenticator.TICKET_HEADER, sign("n1", 60));

        assertThat(authenticator.authenticate(request)).isEmpty();
        verify(provisioningService, never()).bindOrCreate(any());
    }

    @Test
    void emptyWhenSecretMissing() {
        properties.setSecret("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DongbuddyPassiveSessionAuthenticator.TICKET_HEADER, sign("n2", 60));

        assertThat(authenticator.authenticate(request)).isEmpty();
    }

    @Test
    void authenticatesHeaderTicket() {
        PlatformPrincipal principal = new PlatformPrincipal(
            "kb-4abbbd4a", "kb-4abbbd4a", null, null, "dongbuddy", Set.of("USER"));
        when(provisioningService.bindOrCreate(any())).thenReturn(principal);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DongbuddyPassiveSessionAuthenticator.TICKET_HEADER, sign("n3", 60));

        assertThat(authenticator.authenticate(request)).contains(principal);
    }

    @Test
    void authenticatesCookieAndParamTickets() {
        PlatformPrincipal principal = new PlatformPrincipal(
            "kb-4abbbd4a", "kb-4abbbd4a", null, null, "dongbuddy", Set.of("USER"));
        when(provisioningService.bindOrCreate(any())).thenReturn(principal);

        MockHttpServletRequest cookieRequest = new MockHttpServletRequest();
        cookieRequest.setCookies(new jakarta.servlet.http.Cookie(
            DongbuddyPassiveSessionAuthenticator.TICKET_COOKIE, sign("n4", 60)));
        assertThat(authenticator.authenticate(cookieRequest)).contains(principal);

        MockHttpServletRequest paramRequest = new MockHttpServletRequest();
        paramRequest.setParameter(DongbuddyPassiveSessionAuthenticator.TICKET_PARAM, sign("n5", 60));
        assertThat(authenticator.authenticate(paramRequest)).contains(principal);
    }

    @Test
    void rejectsReplayedNonce() {
        PlatformPrincipal principal = new PlatformPrincipal(
            "kb-4abbbd4a", "kb-4abbbd4a", null, null, "dongbuddy", Set.of("USER"));
        when(provisioningService.bindOrCreate(any())).thenReturn(principal);
        String ticket = sign("replay-nonce", 60);
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.addHeader(DongbuddyPassiveSessionAuthenticator.TICKET_HEADER, ticket);
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.addHeader(DongbuddyPassiveSessionAuthenticator.TICKET_HEADER, ticket);

        assertThat(authenticator.authenticate(first)).contains(principal);
        assertThat(authenticator.authenticate(second)).isEmpty();
    }

    @Test
    void rejectsExpiredTicket() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DongbuddyPassiveSessionAuthenticator.TICKET_HEADER, sign("n6", -30));

        assertThat(authenticator.authenticate(request)).isEmpty();
        verify(provisioningService, never()).bindOrCreate(any());
    }

    @Test
    void emptyWhenNoTicketPresent() {
        assertThat(authenticator.authenticate(new MockHttpServletRequest())).isEmpty();
    }

    private static String sign(String nonce, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        String payloadJson = """
            {"phone":"+8619874719079","userId":"kb-4abbbd4a","nonce":"%s","iat":%d,"exp":%d}
            """.formatted(nonce, now - 5, now + ttlSeconds).strip();
        String payloadSegment = ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return payloadSegment + "." + ENCODER.encodeToString(
                mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
