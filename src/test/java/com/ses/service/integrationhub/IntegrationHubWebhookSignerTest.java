package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** B1 outbound signatureの固定framing/golden-vector境界。 */
class IntegrationHubWebhookSignerTest {
    @Test
    void canonicalBytesはfield順とUTF8byte長で一意になる() {
        byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        ApiDelivery delivery = delivery(IntegrationHubDigest.sha256Hex(body));
        IntegrationHubWebhookSigner signer = new IntegrationHubWebhookSigner();

        byte[] canonical = signer.canonicalBytes(delivery, 1, "key-1", 1_756_520_000L, body);
        // 固定golden vector。canonical framingを変更するとsignatureも意図せず変わる。
        assertEquals("-RQHDD7CIETWEElrFgLMdgPR6Ue66oHAhEGkxWBXycQ",
                signer.sign(delivery, 1, "key-1", 1_756_520_000L,
                "test-webhook-secret", body));
        assertEquals(43, signer.sign(delivery, 1, "key-1", 1_756_520_000L,
                "test-webhook-secret", body).length());
        assertArrayEquals(canonical, signer.canonicalBytes(delivery, 1, "key-1", 1_756_520_000L, body));
        assertNotEquals(signer.sign(delivery, 2, "key-1", 1_756_520_000L,
                "test-webhook-secret", body), signer.sign(delivery, 1, "key-1", 1_756_520_000L,
                "test-webhook-secret", body));
        assertNotEquals(signer.sign(delivery, 1, "key-2", 1_756_520_000L,
                "test-webhook-secret", body), signer.sign(delivery, 1, "key-1", 1_756_520_000L,
                "test-webhook-secret", body));
        ApiDelivery changedIdempotency = delivery(IntegrationHubDigest.sha256Hex(body));
        changedIdempotency.setProviderIdempotencyKey(IntegrationHubDigest.sha256Hex("changed-key"));
        assertNotEquals(signer.sign(changedIdempotency, 1, "key-1", 1_756_520_000L,
                "test-webhook-secret", body), signer.sign(delivery, 1, "key-1", 1_756_520_000L,
                "test-webhook-secret", body));
        assertThrows(IllegalArgumentException.class,
                () -> signer.canonicalBytes(delivery, 1, "key-1", 1_756_520_000L,
                        "changed".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureHeaderはv1とpaddingなし32byteへ限定する() {
        IntegrationHubWebhookSigner signer = new IntegrationHubWebhookSigner();
        assertEquals("v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                signer.signatureHeaderValue("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        assertThrows(IllegalArgumentException.class, () -> signer.signatureHeaderValue("bad="));
    }

    private ApiDelivery delivery(String payloadHash) {
        return ApiDelivery.builder().eventId("event-1").eventType("resource.changed")
                .schemaVersion("v1").createdAt(LocalDateTime.of(2026, 8, 31, 12, 0))
                .correlationId("correlation-000001").providerIdempotencyKey(
                        IntegrationHubDigest.sha256Hex("event-1|7|1")).payloadHash(payloadHash).build();
    }
}
