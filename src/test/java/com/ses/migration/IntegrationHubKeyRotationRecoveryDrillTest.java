package com.ses.migration;

import com.ses.entity.integrationhub.CredentialVersion;
import com.ses.service.integrationhub.CredentialVersionService;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M: key rotation overlap/revoke/expiry recovery drillをH2実service経路で固定する。 */
@SpringBootTest(properties = {
        "integration.hub.crypto.current-key-version=test-key-v1",
        "integration.hub.crypto.keys.test-key-v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "integration.hub.crypto.credential-hash-pepper=c3ludGhldGljLW5mMDUtdGVzdC1vbmx5LXBlcHBlciE"
})
@ActiveProfiles("test")
@Transactional
class IntegrationHubKeyRotationRecoveryDrillTest {
    private static final String CLIENT_ID = "m-rotation-client";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 16, 0);
    /** 管理APIが発行する形（32 byte乱数のbase64url、43文字）の合成secret。短いsecretは発行できない（N5-P3-2）。 */
    private static final String SECRET_V1 = "synthetic_rotation_secret_v1_base64url_43ch";
    private static final String SECRET_V2 = "synthetic_rotation_secret_v2_base64url_43ch";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CredentialVersionService credentialVersionService;
    @Autowired
    private IntegrationHubSecretCryptoService cryptoService;

    @Test
    void rotationOverlap中は旧ACTIVEが24時間usableで新ACTIVEへ切替後も旧復号できる() {
        long clientDbId = insertClient();
        CredentialVersion first = credentialVersionService.issue(clientDbId, CLIENT_ID, 1, "key-v1",
                SECRET_V1, NOW);
        assertNotNull(first.getEncryptedSecret());
        assertTrue(first.getEncryptedSecret().startsWith("IHG1:"));

        CredentialVersion second = credentialVersionService.issue(clientDbId, CLIENT_ID, 2, "key-v2",
                SECRET_V2, NOW.plusMinutes(1));
        assertEquals("OVERLAP", jdbcTemplate.queryForObject(
                "SELECT status FROM t_credential_version WHERE credential_version = 1 AND api_client_id = ?",
                String.class, clientDbId));
        assertEquals(NOW.plusMinutes(1).plusHours(24), jdbcTemplate.queryForObject(
                "SELECT overlap_until FROM t_credential_version WHERE credential_version = 1 AND api_client_id = ?",
                LocalDateTime.class, clientDbId));

        assertNotNull(credentialVersionService.findUsable(clientDbId, "key-v1", NOW.plusHours(12)));
        assertNotNull(credentialVersionService.findUsable(clientDbId, "key-v2", NOW.plusHours(12)));
        assertEquals(SECRET_V1,
                cryptoService.decrypt(CLIENT_ID, 1, "credential", first.getEncryptedSecret()));
        assertEquals(SECRET_V2,
                cryptoService.decrypt(CLIENT_ID, 2, "credential", second.getEncryptedSecret()));
    }

    @Test
    void revoke後はusable検索から除外されoverlap期限後も旧世代は失効する() {
        long clientDbId = insertClient();
        credentialVersionService.issue(clientDbId, CLIENT_ID, 1, "key-v1", SECRET_V1, NOW);
        credentialVersionService.issue(clientDbId, CLIENT_ID, 2, "key-v2", SECRET_V2, NOW.plusMinutes(1));

        assertTrue(credentialVersionService.revoke(clientDbId, 2, NOW.plusMinutes(2)));
        assertNull(credentialVersionService.findUsable(clientDbId, "key-v2", NOW.plusMinutes(3)));
        assertNull(credentialVersionService.findUsable(clientDbId, "key-v1", NOW.plusHours(25)));
    }

    private long insertClient() {
        jdbcTemplate.update("DELETE FROM t_credential_version WHERE api_client_id IN "
                + "(SELECT id FROM m_api_client WHERE client_id = ?)", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_api_client WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("""
                INSERT INTO m_api_client (id, client_id, owner_ref, tenant_id, legal_entity_id,
                                          data_scope_json, allowed_cidrs, client_tier, status, version)
                VALUES (9910200, ?, 'PROJECT_OWNER', 'tenant-m-rotation', 73,
                        '{"tenantIds":["tenant-m-rotation"]}', '127.0.0.1/32', 'INTERNAL_TEST', 'ACTIVE', 0)
                """, CLIENT_ID);
        return 9910200L;
    }
}
