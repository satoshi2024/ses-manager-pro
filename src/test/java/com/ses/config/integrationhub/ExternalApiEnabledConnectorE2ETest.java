package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import com.ses.service.integrationhub.IntegrationHubDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手動raw target属性を注入せず、実Tomcat connectorからenabled chainへ到達するE2E。 */
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
class ExternalApiEnabledConnectorE2ETest {
    private static final String CLIENT_ID = "a1-e2e-client";
    private static final String CLIENT_SECRET = "a1-e2e-secret-for-hmac";
    private static final String KEY_ID = "a1-key-1";
    private static final long CLIENT_DB_ID = 9800001L;
    private static final long CREDENTIAL_DB_ID = 9800002L;
    private static final long SCOPE_DB_ID = 9800003L;
    private static final long CUSTOMER_ID = 9800011L;
    private static final long PROJECT_ID = 9800012L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationHubSecretCryptoService cryptoService;

    @BeforeEach
    void insertFixture() {
        deleteFixture();
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name) VALUES (?, ?)",
                CUSTOMER_ID, "a1-e2e-customer");
        jdbcTemplate.update("""
                INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """, PROJECT_ID, "a1-e2e-internal-project", CUSTOMER_ID, "募集中",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));
        String dataScope = "{\"tenantIds\":[\"tenant-a1-e2e\"],\"legalEntityIds\":[\"77\"],"
                + "\"projectIds\":[\"" + PROJECT_ID + "\"],\"customerIds\":[\"" + CUSTOMER_ID + "\"]}";
        jdbcTemplate.update("""
                INSERT INTO m_api_client (id, client_id, owner_ref, tenant_id, legal_entity_id,
                                          data_scope_json, allowed_cidrs, client_tier, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'INTERNAL_TEST', 'ACTIVE', 0)
                """, CLIENT_DB_ID, CLIENT_ID, "PROJECT_OWNER", "tenant-a1-e2e", 77L,
                dataScope, "127.0.0.1/32,::1/128");
        jdbcTemplate.update("""
                INSERT INTO m_api_client_scope (id, api_client_id, scope_code, operation_code,
                                                data_scope_json, status, version)
                VALUES (?, ?, 'integration.project.read', 'integration.project.read', ?, 'ACTIVE', 0)
                """, SCOPE_DB_ID, CLIENT_DB_ID, dataScope);
        Instant now = Instant.now();
        String encryptedSecret = cryptoService.encrypt(CLIENT_ID, 1, "credential", CLIENT_SECRET);
        jdbcTemplate.update("""
                INSERT INTO t_credential_version (id, api_client_id, credential_version, key_id,
                                                  encrypted_secret, secret_hash, crypto_key_version,
                                                  cipher_format, status, issued_at, expires_at, version)
                VALUES (?, ?, 1, ?, ?, ?, 'test-key-v1', 'IHG1', 'ACTIVE', ?, ?, 0)
                """, CREDENTIAL_DB_ID, CLIENT_DB_ID, KEY_ID, encryptedSecret,
                cryptoService.sha256Hex(CLIENT_SECRET),
                java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(86400)));
    }

    @Test
    void enabledChainUsesConnectorRawTargetBeforeAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/external-api/v1/projects", String.class);

        assertEquals(401, response.getStatusCode().value());
        assertTrue(response.getHeaders().containsKey("X-Correlation-ID"));
        assertTrue(response.getBody() != null && response.getBody().contains("AUTHENTICATION_FAILED"));
        assertFalse(response.getBody() != null && response.getBody().contains("RAW_REQUEST_TARGET_UNAVAILABLE"));
    }

    @Test
    void enabledChainAuthenticatesAndReturnsOnlyExternalProjectDto() throws Exception {
        String target = "/external-api/v1/projects?limit=1";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
                UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8));
        String bodyHash = IntegrationHubDigest.sha256Hex(new byte[0]);
        byte[] signedBytes = ExternalApiCanonicalRequest.signedBytes(CLIENT_ID, "1", KEY_ID,
                timestamp, nonce, "GET", target, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signedBytes));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Client-ID", CLIENT_ID);
        headers.set("X-Credential-Version", "1");
        headers.set("X-Key-ID", KEY_ID);
        headers.set("X-Timestamp", timestamp);
        headers.set("X-Nonce", nonce);
        headers.set("X-Client-Signature", signature);
        ResponseEntity<String> response = restTemplate.exchange(target, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().containsKey("X-Correlation-ID"));
        assertTrue(response.getBody() != null && response.getBody().contains("publicProjectId"));
        assertTrue(response.getBody() != null && response.getBody().contains("2026-09-01"));
        assertFalse(response.getBody() != null && response.getBody().contains("internal-project"));
        assertFalse(response.getBody() != null && response.getBody().contains("\"id\""));
        assertFalse(response.getBody() != null && response.getBody().contains("project_name"));
    }

    private void deleteFixture() {
        jdbcTemplate.update("DELETE FROM t_api_nonce_replay WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM t_credential_version WHERE api_client_id = ?", CLIENT_DB_ID);
        jdbcTemplate.update("DELETE FROM m_api_client_scope WHERE api_client_id = ?", CLIENT_DB_ID);
        jdbcTemplate.update("DELETE FROM m_api_client WHERE id = ?", CLIENT_DB_ID);
        jdbcTemplate.update("DELETE FROM t_project WHERE id = ?", PROJECT_ID);
        jdbcTemplate.update("DELETE FROM m_customer WHERE id = ?", CUSTOMER_ID);
    }
}
