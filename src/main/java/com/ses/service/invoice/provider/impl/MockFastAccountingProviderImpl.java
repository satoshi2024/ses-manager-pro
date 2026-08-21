package com.ses.service.invoice.provider.impl;

import com.ses.common.exception.BusinessException;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 開発・test 用 FastAccounting mock（S16-P1-01 / S16-P1-03）。
 * prod プロファイルでは装配されない。署名は設定済み HMAC 秘密鍵のみを受理し、
 * マジック文字列 {@code valid-sig} は通さない。秘密鍵未設定は fail-closed。
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "app.digital-invoice.provider", havingValue = "mock", matchIfMissing = true)
public class MockFastAccountingProviderImpl implements DigitalInvoiceProvider {

    private final Map<String, String> messageIdToProviderId = new ConcurrentHashMap<>();

    @Value("${app.digital-invoice.webhook-hmac-secret:}")
    private String webhookHmacSecret;

    @Override
    public String sendInvoice(String xml, String specificationVersion, String messageId) {
        assertHmacSecretConfigured();
        // Option B: messageId を Idempotency-Key として扱い、同一キーは同一 provider message を返す
        return messageIdToProviderId.computeIfAbsent(messageId,
                id -> "mock-fastaccounting-msg-" + id);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        if (!StringUtils.hasText(webhookHmacSecret)) {
            return false;
        }
        if (!StringUtils.hasText(signatureHeader) || rawBody == null) {
            return false;
        }
        String expected = hmacSha256Hex(webhookHmacSecret, rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    private void assertHmacSecretConfigured() {
        if (!StringUtils.hasText(webhookHmacSecret)) {
            throw new BusinessException(503,
                    "デジタルインボイス Webhook HMAC 秘密鍵が未設定です。送信を拒否します。");
        }
    }

    static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 計算に失敗しました", e);
        }
    }
}
