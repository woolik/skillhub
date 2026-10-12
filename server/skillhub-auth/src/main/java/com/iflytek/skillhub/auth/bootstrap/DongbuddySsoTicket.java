package com.iflytek.skillhub.auth.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wire format + verification for Dongbuddy SSO tickets.
 *
 * <p>Ticket = {@code base64url(payloadJson) + "." + base64url(HMAC-SHA256(secret, payloadSegment))}
 * where payloadJson carries {@code phone}, {@code userId}, {@code nonce},
 * {@code iat} and {@code exp} (epoch seconds). Stateless verification: expiry
 * comes from the payload, replay protection lives in the authenticator's
 * nonce cache.
 */
public final class DongbuddySsoTicket {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public record Payload(String phone, String userId, String nonce, long iat, long exp) {
    }

    private DongbuddySsoTicket() {
    }

    /**
     * Verifies signature and expiry; returns the payload when the ticket is
     * authentic and fresh. Never throws — malformed input yields empty.
     */
    public static Optional<Payload> verify(String ticket, String secret, Instant now) {
        if (ticket == null || secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) {
            return Optional.empty();
        }
        String payloadSegment = ticket.substring(0, dot);
        String signatureSegment = ticket.substring(dot + 1);
        if (!MessageDigest.isEqual(
                hmac(secret, payloadSegment),
                decode(signatureSegment).orElse(null))) {
            return Optional.empty();
        }
        byte[] payloadBytes = decode(payloadSegment).orElse(null);
        if (payloadBytes == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
            String phone = text(node, "phone");
            String userId = text(node, "userId");
            String nonce = text(node, "nonce");
            long iat = node.path("iat").asLong(0);
            long exp = node.path("exp").asLong(0);
            if (phone == null || userId == null || nonce == null) {
                return Optional.empty();
            }
            if (exp <= now.getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(new Payload(phone, userId, nonce, iat, exp));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private static Optional<byte[]> decode(String segment) {
        try {
            return Optional.of(DECODER.decode(segment));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(String secret, String payloadSegment) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return new byte[0];
        }
    }
}
