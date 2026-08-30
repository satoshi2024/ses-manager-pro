package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiDelivery;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** B1 outbound signatureの固定framing/golden-vector境界。 */
class IntegrationHubWebhookSignerTest {
    @Test
    void canonicalBytesはfield順とUTF8byte長で一意になる() {
        byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        ApiDelivery delivery = delivery(IntegrationHubDigest.sha256Hex(body));
        IntegrationHubWebhookSigner signer = new IntegrationHubWebhookSigner();

        byte[] canonical = signer.canonicalBytes(delivery, "key-1", 1_756_520_000L, body);
        // 固定golden vector。canonical framingを変更するとsignatureも意図せず変わる。
        assertEquals("GR1ZETro6wTTc6PJn1mVpAvAxdZ3O2rpmHL4RKCi314",
                signer.sign(delivery, "key-1", 1_756_520_000L,
                "test-webhook-secret", body));
        assertEquals(43, signer.sign(delivery, "key-1", 1_756_520_000L,
                "test-webhook-secret", body).length());
        assertArrayEquals(canonical, signer.canonicalBytes(delivery, "key-1", 1_756_520_000L, body));
        assertThrows(IllegalArgumentException.class,
                () -> signer.canonicalBytes(delivery, "key-1", 1_756_520_000L,
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
                .correlationId("correlation-000001").payloadHash(payloadHash).build();
    }
}
