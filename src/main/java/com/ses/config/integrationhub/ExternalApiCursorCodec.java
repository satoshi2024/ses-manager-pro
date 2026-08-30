package com.ses.config.integrationhub;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/** 内部IDを平文にせず、client/scope/as-ofへbindしたopaque cursor。 */
@Component
@RequiredArgsConstructor
public class ExternalApiCursorCodec {
    private static final String AAD = "integration-hub-cursor-v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final IntegrationHubExternalApiProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public String encode(State state) {
        if (state == null || state.lastInternalId() < 1 || state.expiresAtEpochSecond() <= state.asOfEpochSecond()) {
            throw new IllegalArgumentException("cursor state is invalid");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes(), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(payload(state).getBytes(StandardCharsets.UTF_8));
            String token = "v1." + b64(iv) + "." + b64(encrypted);
            if (token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException("cursor is too large");
            }
            return token;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cursor codec is unavailable", e);
        }
    }

    public State decode(String token, String expectedClientId, String expectedTenantId,
                        Long expectedLegalEntityId, String expectedRoute, String expectedScopeDigest,
                        Instant now) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH || !token.startsWith("v1.")) {
                throw invalid();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                throw invalid();
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            if (iv.length != IV_BYTES || encrypted.length < 16 || encrypted.length > 1024) {
                throw invalid();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes(), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.US_ASCII));
            State state = parse(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            if (!state.clientId().equals(expectedClientId) || !state.tenantId().equals(expectedTenantId)
                    || state.legalEntityId() != expectedLegalEntityId
                    || !state.routeTemplate().equals(expectedRoute)
                    || !state.scopeDigest().equals(expectedScopeDigest)
                    || state.expiresAtEpochSecond() <= now.getEpochSecond()) {
                throw invalid();
            }
            return state;
        } catch (ExternalApiSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    public long expiryFrom(Instant now) {
        int ttl = properties.getPublicApi() == null ? 0 : properties.getPublicApi().getCursorTtlSeconds();
        if (ttl < 1 || ttl > 3600) {
            throw new IllegalStateException("cursor ttl is outside the approved bound");
        }
        return now.getEpochSecond() + ttl;
    }

    private String payload(State state) {
        return String.join("|", state.clientId(), state.tenantId(), Long.toString(state.legalEntityId()),
                state.routeTemplate(), state.scopeDigest(), Long.toString(state.asOfEpochSecond()),
                Long.toString(state.lastInternalId()), Long.toString(state.expiresAtEpochSecond()));
    }

    private State parse(String payload) {
        String[] values = payload.split("\\|", -1);
        if (values.length != 8 || values[0].isBlank() || values[1].isBlank() || values[3].isBlank()
                || !values[1].matches("[A-Za-z0-9._~:-]{1,128}")
                || !values[3].matches("/[A-Za-z0-9._~:/{}-]{1,256}")
                || !values[4].matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        try {
            long legalEntityId = Long.parseLong(values[2]);
            long asOf = Long.parseLong(values[5]);
            long lastId = Long.parseLong(values[6]);
            long expires = Long.parseLong(values[7]);
            if (legalEntityId < 1 || asOf < 1 || lastId < 1 || expires <= asOf) {
                throw invalid();
            }
            return new State(values[0], values[1], legalEntityId, values[3], values[4], asOf, lastId, expires);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private byte[] keyBytes() {
        String key = properties.getPublicApi() == null ? null : properties.getPublicApi().getPublicIdKey();
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("integration.hub.public-api.public-id-key is not configured");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("integration-hub-cursor-key|" + key).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private ExternalApiSecurityException invalid() {
        return ExternalApiSecurityException.invalid("CURSOR_INVALID");
    }

    public record State(String clientId, String tenantId, long legalEntityId, String routeTemplate,
                        String scopeDigest, long asOfEpochSecond, long lastInternalId,
                        long expiresAtEpochSecond) {
    }
}
