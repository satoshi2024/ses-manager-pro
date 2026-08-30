package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * B1 webhook HMAC-SHA256 signer。
 *
 * <p>曖昧な文字列連結を避けるため、固定prefixの後に各fieldをUTF-8 byte length
 * prefix（ASCII decimal + ':'）とLFでframingする。最後のfieldは送信bodyそのものの
 * bytesであり、DBへ保存済みのpayload hashとも照合する。
 */
public final class IntegrationHubWebhookSigner {
    public static final String SIGNATURE_VERSION = "v1";
    private static final String PREFIX = "IH-WEBHOOK-1\n";
    private static final DateTimeFormatter CREATED_AT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public String sign(ApiDelivery delivery, int credentialVersion, String keyId, long timestampEpochSeconds, String secret,
                       byte[] body) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(secret, canonicalBytes(delivery, credentialVersion, keyId, timestampEpochSeconds, body)));
    }

    public byte[] canonicalBytes(ApiDelivery delivery, int credentialVersion, String keyId,
                                 long timestampEpochSeconds, byte[] body) {
        if (delivery == null || credentialVersion <= 0 || keyId == null || keyId.isBlank() || keyId.length() > 100
                || timestampEpochSeconds <= 0 || body == null || body.length == 0
                || !IntegrationHubDigest.sha256Hex(body).equalsIgnoreCase(delivery.getPayloadHash())
                || delivery.getCreatedAt() == null || delivery.getProviderIdempotencyKey() == null
                || !delivery.getProviderIdempotencyKey().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invalid webhook signing input");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, PREFIX);
        writeField(out, delivery.getEventId());
        writeField(out, delivery.getEventType());
        writeField(out, delivery.getSchemaVersion());
        writeField(out, CREATED_AT_FORMAT.format(delivery.getCreatedAt()));
        writeField(out, delivery.getCorrelationId() == null ? "" : delivery.getCorrelationId());
        writeField(out, Long.toString(timestampEpochSeconds));
        writeField(out, Integer.toString(credentialVersion));
        writeField(out, keyId);
        writeField(out, delivery.getProviderIdempotencyKey());
        writeField(out, delivery.getPayloadHash());
        writeField(out, body);
        return out.toByteArray();
    }

    public String signatureHeaderValue(String signature) {
        if (signature == null || !signature.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("invalid webhook signature");
        }
        return SIGNATURE_VERSION + "=" + signature;
    }

    private byte[] hmac(String secret, byte[] canonical) {
        if (secret == null || secret.isBlank() || canonical == null) {
            throw new IllegalArgumentException("invalid webhook signing secret");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return mac.doFinal(canonical);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("webhook HMAC is unavailable", e);
        } finally {
            java.util.Arrays.fill(secretBytes, (byte) 0);
        }
    }

    private void writeField(ByteArrayOutputStream out, String value) {
        if (value == null) {
            throw new IllegalArgumentException("webhook signing field is missing");
        }
        writeField(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeField(ByteArrayOutputStream out, byte[] value) {
        if (value == null || value.length > IntegrationHubWebhookRequest.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("webhook signing field is too large");
        }
        writeAscii(out, Integer.toString(value.length));
        writeAscii(out, ":");
        out.writeBytes(value);
        out.write('\n');
    }

    private void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }
}
