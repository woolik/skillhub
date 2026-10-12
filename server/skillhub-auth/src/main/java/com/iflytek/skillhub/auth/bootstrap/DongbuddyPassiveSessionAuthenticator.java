package com.iflytek.skillhub.auth.bootstrap;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Passive session authenticator for the Dongbuddy desktop gateway.
 *
 * <p>Dongbuddy signs a short-lived HMAC ticket (phone + kb- username) and
 * delivers it either server-to-server ({@code X-Dongbuddy-Sso} header / query
 * param, used by the engine-gateway's proxied API calls) or via the browser
 * ({@code db_sso} cookie set by the {@code /sso/dongbuddy} exchange page right
 * before it calls {@code POST /api/v1/auth/session/bootstrap}).
 *
 * <p>Tickets are single-use: the nonce is remembered until the ticket's own
 * expiry so a replayed ticket (browser history, logged curl) never mints a
 * second session.
 */
@Component
public class DongbuddyPassiveSessionAuthenticator implements PassiveSessionAuthenticator {

    public static final String TICKET_HEADER = "X-Dongbuddy-Sso";
    public static final String TICKET_COOKIE = "db_sso";
    public static final String TICKET_PARAM = "ticket";

    private static final int NONCE_SWEEP_THRESHOLD = 4096;

    private final DongbuddySsoProperties properties;
    private final DongbuddySsoProvisioningService provisioningService;
    /** nonce → ticket expiry (epoch seconds); entries die with their ticket. */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public DongbuddyPassiveSessionAuthenticator(DongbuddySsoProperties properties,
                                                DongbuddySsoProvisioningService provisioningService) {
        this.properties = properties;
        this.provisioningService = provisioningService;
    }

    @Override
    public String providerCode() {
        return DongbuddySsoProperties.PROVIDER_CODE;
    }

    @Override
    public String displayName() {
        return "Dongbuddy";
    }

    @Override
    public Optional<PlatformPrincipal> authenticate(HttpServletRequest request) {
        if (!properties.isUsable()) {
            return Optional.empty();
        }
        String ticket = extractTicket(request);
        if (ticket == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Optional<DongbuddySsoTicket.Payload> payload =
            DongbuddySsoTicket.verify(ticket, properties.getSecret(), now);
        if (payload.isEmpty() || !claimNonce(payload.get().nonce(), payload.get().exp())) {
            return Optional.empty();
        }
        try {
            return Optional.of(provisioningService.bindOrCreate(payload.get()));
        } catch (RuntimeException ex) {
            // Provisioning failures (disabled account, slug races) must not
            // leak details — the caller surfaces a plain 401.
            return Optional.empty();
        }
    }

    private boolean claimNonce(String nonce, long exp) {
        if (seenNonces.size() > NONCE_SWEEP_THRESHOLD) {
            long nowSec = Instant.now().getEpochSecond();
            seenNonces.entrySet().removeIf(entry -> entry.getValue() < nowSec);
        }
        return seenNonces.putIfAbsent(nonce, exp) == null;
    }

    private String extractTicket(HttpServletRequest request) {
        String header = request.getHeader(TICKET_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (TICKET_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue().trim();
                }
            }
        }
        String param = request.getParameter(TICKET_PARAM);
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }
}
