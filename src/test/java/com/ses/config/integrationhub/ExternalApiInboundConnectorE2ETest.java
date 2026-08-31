package com.ses.config.integrationhub;

import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手動body/raw attribute注入なしで、Tomcat connectorからinbound duplicate/conflictまで検証する。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "integration.hub.public-api.enabled=true",
                "integration.hub.public-api.public-id-key=test-integration-hub-public-id-key-at-least-32-bytes",
                "integration.hub.external-transport.enabled=false",
                "integration.hub.provider.mode=MOCK",
                "integration.hub.crypto.current-key-version=test-key-v1",
                "integration.hub.crypto.keys.test-key-v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        })
@ActiveProfiles("test")
@Tag("browser")
class ExternalApiInboundConnectorE2ETest {
    private static final String CLIENT_ID = "b2-e2e-client";
    private static final String CLIENT_SECRET = "b2-e2e-secret-for-hmac";
    private static final String KEY_ID = "b2-key-1";
    private static final long CLIENT_DB_ID = 9810001L;
    private static final long CREDENTIAL_DB_ID = 9810002L;
    private static final long SCOPE_DB_ID = 9810003L;
    private static final String TARGET = "/external-api/v1/webhooks/provider-b2";
    private static final String EVENT_ID = "b2-e2e-event-1";
    private static final String BODY = "{\"providerEventId\":\"b2-e2e-event-1\","
            + "\"eventType\":\"resource.changed\",\"canonicalPayload\":{\"status\":\"ACTIVE\"}}";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IntegrationHubSecretCryptoService cryptoService;

    @BeforeEach
    void insertFixture() {
        deleteFixture();
        String scope = "{\"tenantIds\":[\"tenant-b2-e2e\"],\"legalEntityIds\":[\"88\"]}";
        jdbcTemplate.update("INSERT INTO m_api_client (id, client_id, owner_ref, tenant_id, legal_entity_id, "
                        + "data_scope_json, allowed_cidrs, client_tier, status, version) VALUES "
                        + "(?, ?, 'PROJECT_OWNER', 'tenant-b2-e2e', 88, ?, '127.0.0.1/32,::1/128', "
                        + "'INTERNAL_TEST', 'ACTIVE', 0)", CLIENT_DB_ID, CLIENT_ID, scope);
        jdbcTemplate.update("INSERT INTO m_api_client_scope (id, api_client_id, scope_code, operation_code, "
                        + "data_scope_json, status, version) VALUES (?, ?, 'integration.webhook.receive', "
                        + "'integration.webhook.receive', ?, 'ACTIVE', 0)", SCOPE_DB_ID, CLIENT_DB_ID, scope);
        jdbcTemplate.update("INSERT INTO m_webhook_subscription (client_id, direction, event_type, endpoint_url, "
                        + "key_id, encrypted_signing_secret, crypto_key_version, data_scope_json, status, version) "
                        + "VALUES (?, 'INBOUND', 'resource.changed', 'https://unused.invalid/inbound-b2', 'b2-key', "
                        + "'IHG1:v1:iv:cipher', 'v1', ?, 'ACTIVE', 0)", CLIENT_ID, scope);
        Instant now = Instant.now();
        String encryptedSecret = cryptoService.encrypt(CLIENT_ID, 1, "credential", CLIENT_SECRET);
        jdbcTemplate.update("INSERT INTO t_credential_version (id, api_client_id, credential_version, key_id, "
                        + "encrypted_secret, secret_hash, crypto_key_version, cipher_format, status, issued_at, expires_at, version) "
                        + "VALUES (?, ?, 1, ?, ?, ?, 'test-key-v1', 'IHG1', 'ACTIVE', ?, ?, 0)",
                CREDENTIAL_DB_ID, CLIENT_DB_ID, KEY_ID, encryptedSecret, cryptoService.sha256Hex(CLIENT_SECRET),
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.plusSeconds(86400), ZoneOffset.UTC));
    }

    @Test
    void connectorInboundは初回を処理し同hashをduplicate別hashをconflictにする() throws Exception {
        ResponseEntity<String> first = send(BODY);
        assertEquals(202, first.getStatusCode().value());
        assertTrue(first.getBody() != null && first.getBody().contains("PROCESSED"));

        ResponseEntity<String> duplicate = send(BODY);
        assertEquals(200, duplicate.getStatusCode().value());
        assertTrue(duplicate.getBody() != null && duplicate.getBody().contains("\"duplicate\":true"));

        String conflictingBody = BODY.replace("ACTIVE", "INACTIVE");
        ResponseEntity<String> conflict = send(conflictingBody);
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(conflict.getBody() != null && conflict.getBody().contains("INBOUND_PAYLOAD_CONFLICT"));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_inbound_event "
                + "WHERE client_id = ? AND provider_event_id = ? AND status = 'PROCESSED'", Integer.class,
                CLIENT_ID, EVENT_ID));
    }

    private ResponseEntity<String> send(String body) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
                UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8));
        String bodyHash = IntegrationHubDigest.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        byte[] signed = ExternalApiCanonicalRequest.signedBytes(CLIENT_ID, "1", KEY_ID, timestamp, nonce,
                "POST", TARGET, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signed));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Client-ID", CLIENT_ID);
        headers.set("X-Credential-Version", "1");
        headers.set("X-Key-ID", KEY_ID);
        headers.set("X-Timestamp", timestamp);
        headers.set("X-Nonce", nonce);
        headers.set("X-Client-Signature", signature);
        headers.set("X-Provider-Event-ID", EVENT_ID);
        return restTemplate.exchange(TARGET, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void deleteFixture() {
        jdbcTemplate.update("DELETE FROM t_api_nonce_replay WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM t_inbound_event_replay WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM t_inbound_event WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_webhook_subscription WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_api_client_scope WHERE api_client_id = ?", CLIENT_DB_ID);
        jdbcTemplate.update("DELETE FROM t_credential_version WHERE api_client_id = ?", CLIENT_DB_ID);
        jdbcTemplate.update("DELETE FROM m_api_client WHERE id = ?", CLIENT_DB_ID);
    }
}
