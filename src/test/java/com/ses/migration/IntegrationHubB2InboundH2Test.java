package com.ses.migration;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.service.integrationhub.ApiRetentionPurgeService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.InboundEventAdminService;
import com.ses.service.integrationhub.InboundEventService;
import com.ses.service.integrationhub.IntegrationHubStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B2 inboundのduplicate/conflict/replay/purgeをH2の実mapper経路で確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationHubB2InboundH2Test {
    private static final String CLIENT_ID = "b2-h2-client";
    private static final String PROVIDER = "provider-b2";
    private static final String EVENT_ID = "event-b2-1";
    private static final String TENANT = "tenant-b2";
    private static final long LEGAL_ENTITY = 55L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0);
    private static final String HASH_1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String HASH_2 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private InboundEventService inboundEventService;
    @Autowired
    private InboundEventAdminService inboundEventAdminService;
    @Autowired
    private ApiRetentionPurgeService retentionPurgeService;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_inbound_event_replay WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM t_inbound_event WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_webhook_subscription WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_api_client_scope WHERE api_client_id IN "
                + "(SELECT id FROM m_api_client WHERE client_id = ?)", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_api_client WHERE client_id = ?", CLIENT_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 同一eventの同hashはduplicateで別hashはconflictになりterminalを逆遷移させない() {
        ExternalDtoSnapshot snapshot = snapshot();
        InboundEventService.Receipt first = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_1, NOW, snapshot, true, NOW);
        InboundEvent claimed = inboundEventService.claim(first.event().getId(), NOW);
        assertTrue(inboundEventService.complete(claimed.getId(), claimed.getVersion(),
                IntegrationHubStates.INBOUND_PROCESSED, "INBOUND_ACCEPTED", NOW));

        InboundEventService.Receipt duplicate = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_1, NOW, snapshot, true, NOW);
        assertTrue(duplicate.duplicate());
        assertEquals(IntegrationHubStates.INBOUND_PROCESSED, duplicate.event().getStatus());

        InboundEventService.Receipt conflict = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_2, NOW, snapshot, true, NOW);
        assertTrue(conflict.conflict());
        assertEquals(IntegrationHubStates.INBOUND_PROCESSED, conflict.event().getStatus());
    }

    @Test
    void adminReplayはderivedOperatorとcurrentBindingを使い元DLQを変更せずmetadataだけをpurgeする() {
        long clientDbId = insertBinding();
        InboundEvent event = insertDlqEvent();
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);

        var replay = inboundEventAdminService.replay(event.getId(), "INCIDENT_RECOVERY", admin, NOW);
        var processed = inboundEventAdminService.processReplay(replay.requestId(), admin, NOW);

        assertEquals("PROCESSED", processed.status());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_inbound_event WHERE id = ? AND status = 'DLQ'", Integer.class, event.getId()));
        assertEquals("sys-user:7001", jdbcTemplate.queryForObject(
                "SELECT operator_ref FROM t_inbound_event_replay WHERE id = ?", String.class, replay.requestId()));
        jdbcTemplate.update("UPDATE t_inbound_event_replay SET retention_expires_at = ? WHERE id = ?",
                NOW.minusSeconds(1), replay.requestId());
        assertEquals(1, retentionPurgeService.purgeExpired(
                "INBOUND_REPLAY", IntegrationHubStates.RETENTION_AUDIT_1Y, NOW, 10).purged());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_inbound_event_replay WHERE id = ?", Integer.class, replay.requestId()));
        // client DB id is only used to ensure the fixture was really bound; it is never returned by the service.
        assertTrue(clientDbId > 0);
    }

    @Test
    void 非adminはreplay権限境界で拒否される() {
        insertBinding();
        InboundEvent event = insertDlqEvent();
        Authentication sales = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "7002", "N/A", List.of(new SimpleGrantedAuthority("ROLE_営業")));
        SecurityContextHolder.getContext().setAuthentication(sales);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> inboundEventAdminService.replay(event.getId(), "INCIDENT_RECOVERY", sales, NOW));
    }

    @Test
    void replay直前のscope変更はcurrentBinding不一致として拒否される() {
        long clientDbId = insertBinding();
        InboundEvent event = insertDlqEvent();
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);
        jdbcTemplate.update("UPDATE m_api_client_scope SET data_scope_json = ? WHERE api_client_id = ?",
                "{\"tenantIds\":[\"tenant-b2\"],\"legalEntityIds\":[\"999\"]}", clientDbId);

        assertThrows(SecurityException.class,
                () -> inboundEventAdminService.replay(event.getId(), "INCIDENT_RECOVERY", admin, NOW));
    }

    @Test
    void replay要求後のscope縮小はprocessorへ渡さずrejectedへ終端する() {
        long clientDbId = insertBinding();
        InboundEvent event = insertDlqEvent();
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);

        var replay = inboundEventAdminService.replay(event.getId(), "INCIDENT_RECOVERY", admin, NOW);
        jdbcTemplate.update("UPDATE m_api_client_scope SET data_scope_json = ? WHERE api_client_id = ?",
                "{\"tenantIds\":[\"tenant-b2\"],\"legalEntityIds\":[\"999\"]}", clientDbId);

        var result = inboundEventAdminService.processReplay(replay.requestId(), admin, NOW);

        assertEquals("REJECTED", result.status());
        assertEquals("CURRENT_SCOPE_INVALID", result.resultCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_inbound_event WHERE id = ? AND status = 'DLQ'", Integer.class, event.getId()));
    }

    private ExternalDtoSnapshot snapshot() {
        return ExternalDtoSnapshot.ofAllowList(
                "{\"providerEventId\":\"" + EVENT_ID + "\",\"provider\":\"" + PROVIDER
                        + "\",\"eventType\":\"resource.changed\",\"signatureResult\":\"VALID\"}",
                ExternalDtoSnapshot.INBOUND_FIELDS);
    }

    private long insertBinding() {
        String scope = "{\"tenantIds\":[\"" + TENANT + "\"],\"legalEntityIds\":[\""
                + LEGAL_ENTITY + "\"],\"projectIds\":[\"101\"]}";
        jdbcTemplate.update("INSERT INTO m_api_client (client_id, owner_ref, tenant_id, legal_entity_id, "
                        + "data_scope_json, allowed_cidrs, client_tier, status, version) VALUES "
                        + "(?, 'PROJECT_OWNER', ?, ?, ?, '127.0.0.1/32', 'INTERNAL_TEST', 'ACTIVE', 0)",
                CLIENT_ID, TENANT, LEGAL_ENTITY, scope);
        long id = jdbcTemplate.queryForObject("SELECT id FROM m_api_client WHERE client_id = ?", Long.class, CLIENT_ID);
        String receiveScope = "{\"tenantIds\":[\"" + TENANT + "\"],\"legalEntityIds\":[\""
                + LEGAL_ENTITY + "\"]}";
        jdbcTemplate.update("INSERT INTO m_api_client_scope (api_client_id, scope_code, operation_code, "
                        + "data_scope_json, status, version) VALUES (?, 'integration.webhook.receive', "
                        + "'integration.webhook.receive', ?, 'ACTIVE', 0)", id, receiveScope);
        jdbcTemplate.update("INSERT INTO m_webhook_subscription (client_id, direction, event_type, endpoint_url, "
                        + "key_id, encrypted_signing_secret, crypto_key_version, data_scope_json, status, version) "
                        + "VALUES (?, 'INBOUND', 'resource.changed', 'https://unused.invalid/inbound', 'key-b2', "
                        + "'IHG1:v1:iv:cipher', 'v1', ?, 'ACTIVE', 0)", CLIENT_ID, receiveScope);
        return id;
    }

    private InboundEvent insertDlqEvent() {
        jdbcTemplate.update("INSERT INTO t_inbound_event (client_id, provider_name, provider_event_id, "
                        + "raw_body_hash, signed_timestamp, parsed_fields_snapshot, signature_valid, status, "
                        + "result_code, received_at, processed_at, terminal_at, retention_class, retention_expires_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE, 'DLQ', 'INBOUND_PROCESSING_FAILED', ?, ?, ?, ?, ?, 0)",
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_1, NOW, snapshot().json(), NOW, NOW, NOW,
                IntegrationHubStates.RETENTION_FAILED_90D, NOW.plusDays(90));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM t_inbound_event WHERE client_id = ? "
                + "AND provider_event_id = ?", Long.class, CLIENT_ID, EVENT_ID);
        return InboundEvent.builder().id(id).status(IntegrationHubStates.INBOUND_DLQ).build();
    }

    private Authentication adminAuthentication() {
        SysUser user = SysUser.builder().username("b2-admin").role("管理者").status(1).build();
        user.setId(7001L);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者"))), "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
    }
}
