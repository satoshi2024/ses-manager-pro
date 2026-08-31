package com.ses.migration;

import com.ses.config.LoginUser;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final String LEASE_TOKEN = "b2-h2-lease";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private InboundEventService inboundEventService;
    @Autowired
    private InboundEventAdminService inboundEventAdminService;
    @Autowired
    private ApiRetentionPurgeService retentionPurgeService;
    @Autowired
    private ExternalApiPublicIdCodec publicIdCodec;

    private static final long RESOURCE_CUSTOMER_ID = 9900201L;
    private static final long RESOURCE_REPARENT_CUSTOMER_ID = 9900202L;
    private static final long RESOURCE_PROJECT_ID = 9900203L;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_inbound_event_replay WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM t_inbound_event WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_webhook_subscription WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_api_client_scope WHERE api_client_id IN "
                + "(SELECT id FROM m_api_client WHERE client_id = ?)", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_api_client WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM t_project WHERE id = ?", RESOURCE_PROJECT_ID);
        jdbcTemplate.update("DELETE FROM m_customer WHERE id IN (?, ?)", RESOURCE_CUSTOMER_ID,
                RESOURCE_REPARENT_CUSTOMER_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 同一eventの同hashはduplicateで別hashはconflictになりterminalを逆遷移させない() {
        insertBinding();
        ExternalDtoSnapshot snapshot = snapshot();
        InboundEventService.Receipt first = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_1, NOW, snapshot, true, NOW);
        InboundEvent claimed = claimEvent(first.event().getId());
        assertTrue(completeEvent(claimed, IntegrationHubStates.INBOUND_PROCESSED, "INBOUND_ACCEPTED"));

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
    void processing中の同hash再送はinProgressとなりterminal偽duplicateを返さない() {
        insertBinding();
        ExternalDtoSnapshot snapshot = snapshot();
        InboundEventService.Receipt first = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_1, NOW, snapshot, true, NOW);
        assertTrue(claimEvent(first.event().getId()) != null);

        InboundEventService.Receipt inProgress = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_1, NOW, snapshot, true, NOW);

        assertTrue(inProgress.inProgress());
        assertFalse(inProgress.duplicate());
        assertEquals(IntegrationHubStates.INBOUND_PROCESSING, inProgress.event().getStatus());
    }

    @Test
    void claim後crashのstaleLeaseはrecoverExpiredLeasesでRECEIVEDへ復帰し再claimできる() {
        insertBinding();
        InboundEventService.Receipt receipt = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, "event-b2-lease", HASH_1, NOW, snapshot(), true, NOW);
        assertTrue(claimEvent(receipt.event().getId()) != null);

        assertEquals(0, inboundEventService.recoverExpiredLeases(NOW.plusMinutes(4)));
        assertEquals(1, inboundEventService.recoverExpiredLeases(NOW.plusMinutes(6)));
        assertEquals("RECEIVED", jdbcTemplate.queryForObject(
                "SELECT status FROM t_inbound_event WHERE id = ?", String.class, receipt.event().getId()));

        InboundEvent reclaimed = inboundEventService.claim(receipt.event().getId(), "b2-recover-lease",
                NOW, NOW.plusMinutes(5));
        assertEquals(IntegrationHubStates.INBOUND_PROCESSING, reclaimed.getStatus());
        assertTrue(inboundEventService.complete(reclaimed.getId(), reclaimed.getVersion(), "b2-recover-lease",
                IntegrationHubStates.INBOUND_PROCESSED, "INBOUND_ACCEPTED", NOW));
    }

    @Test
    void recover後の同hash再送はrecordReceived経由で409にならずclaimしてPROCESSEDへ収束する() {
        insertBinding();
        ExternalDtoSnapshot snapshot = snapshot();
        String eventId = "event-b2-record-retry";
        InboundEventService.Receipt first = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, eventId, HASH_1, NOW, snapshot, true, NOW);
        assertTrue(claimEvent(first.event().getId()) != null);

        LocalDateTime afterLeaseExpiry = NOW.plusMinutes(6);
        assertEquals(1, inboundEventService.recoverExpiredLeases(afterLeaseExpiry));

        InboundEventService.Receipt retry = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, eventId, HASH_1, afterLeaseExpiry, snapshot, true, afterLeaseExpiry);
        assertFalse(retry.conflict());
        assertFalse(retry.duplicate());
        assertFalse(retry.inProgress());
        assertEquals(IntegrationHubStates.INBOUND_RECEIVED, retry.event().getStatus());

        InboundEvent claimed = inboundEventService.claim(retry.event().getId(), "b2-retry-lease",
                afterLeaseExpiry, afterLeaseExpiry.plusMinutes(5));
        assertEquals(IntegrationHubStates.INBOUND_PROCESSING, claimed.getStatus());
        assertTrue(inboundEventService.complete(claimed.getId(), claimed.getVersion(), "b2-retry-lease",
                IntegrationHubStates.INBOUND_PROCESSED, "INBOUND_ACCEPTED", afterLeaseExpiry));
    }

    @Test
    void adminReplayはderivedOperatorとcurrentBindingを使い元DLQを変更せずmetadataだけをpurgeする() {
        long clientDbId = insertBinding();
        InboundEvent event = insertDlqEvent();
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);

        String eventReference = jdbcTemplate.queryForObject(
                "SELECT admin_reference FROM t_inbound_event WHERE id = ?", String.class, event.getId());
        var replay = inboundEventAdminService.replay(eventReference, "INCIDENT_RECOVERY", admin, NOW);
        var processed = inboundEventAdminService.processReplay(replay.replayReference(), admin, NOW);

        assertEquals("PROCESSED", processed.status());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_inbound_event WHERE id = ? AND status = 'DLQ'", Integer.class, event.getId()));
        assertEquals("sys-user:7001", jdbcTemplate.queryForObject(
                "SELECT operator_ref FROM t_inbound_event_replay WHERE replay_reference = ?", String.class,
                replay.replayReference()));
        jdbcTemplate.update("UPDATE t_inbound_event_replay SET retention_expires_at = ? WHERE replay_reference = ?",
                NOW.minusSeconds(1), replay.replayReference());
        assertEquals(1, retentionPurgeService.purgeExpired(
                "INBOUND_REPLAY", IntegrationHubStates.RETENTION_AUDIT_1Y, NOW, 10).purged());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_inbound_event_replay WHERE replay_reference = ?", Integer.class,
                replay.replayReference()));
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

        String eventReference = jdbcTemplate.queryForObject(
                "SELECT admin_reference FROM t_inbound_event WHERE id = ?", String.class, event.getId());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> inboundEventAdminService.replay(eventReference, "INCIDENT_RECOVERY", sales, NOW));
    }

    @Test
    void replay直前のscope変更はcurrentBinding不一致として拒否される() {
        long clientDbId = insertBinding();
        InboundEvent event = insertDlqEvent();
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);
        jdbcTemplate.update("UPDATE m_api_client_scope SET data_scope_json = ? WHERE api_client_id = ?",
                "{\"tenantIds\":[\"tenant-b2\"],\"legalEntityIds\":[\"999\"]}", clientDbId);

        String eventReference = jdbcTemplate.queryForObject(
                "SELECT admin_reference FROM t_inbound_event WHERE id = ?", String.class, event.getId());
        assertThrows(SecurityException.class,
                () -> inboundEventAdminService.replay(eventReference, "INCIDENT_RECOVERY", admin, NOW));
    }

    @Test
    void replay要求後のscope縮小はprocessorへ渡さずrejectedへ終端する() {
        long clientDbId = insertBinding();
        InboundEvent event = insertDlqEvent();
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);

        String eventReference = jdbcTemplate.queryForObject(
                "SELECT admin_reference FROM t_inbound_event WHERE id = ?", String.class, event.getId());
        var replay = inboundEventAdminService.replay(eventReference, "INCIDENT_RECOVERY", admin, NOW);
        jdbcTemplate.update("UPDATE m_api_client_scope SET data_scope_json = ? WHERE api_client_id = ?",
                "{\"tenantIds\":[\"tenant-b2\"],\"legalEntityIds\":[\"999\"]}", clientDbId);

        var result = inboundEventAdminService.processReplay(replay.replayReference(), admin, NOW);

        assertEquals("REJECTED", result.status());
        assertEquals("CURRENT_SCOPE_INVALID", result.resultCode());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_inbound_event WHERE id = ? AND status = 'DLQ'", Integer.class, event.getId()));
    }

    @Test
    void resourceBindingはprimaryとsecondaryをopaque再計算しreparent後のreplayを拒否する() {
        insertResourceFixtures();
        insertBinding("project.changed");
        ExternalApiPrincipal principal = new ExternalApiPrincipal(CLIENT_ID, clientDbId(), TENANT,
                LEGAL_ENTITY, null, 1, "inbound", "INTERNAL_TEST");
        String projectPublicId = publicIdCodec.encode(principal, "project", RESOURCE_PROJECT_ID);
        String customerPublicId = publicIdCodec.encode(principal, "customer", RESOURCE_CUSTOMER_ID);
        String eventId = "project-resource-event";
        String json = "{\"providerEventId\":\"" + eventId + "\",\"provider\":\"" + PROVIDER
                + "\",\"eventType\":\"project.changed\",\"signatureResult\":\"VALID\","
                + "\"canonicalPayload\":{\"publicProjectId\":\"" + projectPublicId
                + "\",\"publicCustomerId\":\"" + customerPublicId + "\",\"status\":\"ACTIVE\"}}";
        ExternalDtoSnapshot resourceSnapshot = ExternalDtoSnapshot.ofAllowList(
                json, ExternalDtoSnapshot.INBOUND_FIELDS);
        InboundEventService.Receipt receipt = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, eventId, HASH_1, NOW, resourceSnapshot, true, NOW);

        assertEquals("project", receipt.event().getPrimaryResourceType());
        assertEquals(RESOURCE_PROJECT_ID, receipt.event().getPrimaryResourceId());
        InboundEvent claimed = claimEvent(receipt.event().getId());
        assertTrue(completeEvent(claimed, IntegrationHubStates.INBOUND_DLQ, "INBOUND_PROCESSING_FAILED"));
        String eventReference = receipt.event().getAdminReference();
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);
        var replay = inboundEventAdminService.replay(eventReference, "INCIDENT_RECOVERY", admin, NOW);

        jdbcTemplate.update("UPDATE t_project SET customer_id = ? WHERE id = ?",
                RESOURCE_REPARENT_CUSTOMER_ID, RESOURCE_PROJECT_ID);
        var result = inboundEventAdminService.processReplay(replay.replayReference(), admin, NOW);

        assertEquals("REJECTED", result.status());
        assertEquals("CURRENT_SCOPE_INVALID", result.resultCode());
    }

    @Test
    void resourceBindingはprimaryのsoftDelete後のreplayを拒否する() {
        insertResourceFixtures();
        insertBinding("project.changed");
        ExternalApiPrincipal principal = new ExternalApiPrincipal(CLIENT_ID, clientDbId(), TENANT,
                LEGAL_ENTITY, null, 1, "inbound", "INTERNAL_TEST");
        String projectPublicId = publicIdCodec.encode(principal, "project", RESOURCE_PROJECT_ID);
        String customerPublicId = publicIdCodec.encode(principal, "customer", RESOURCE_CUSTOMER_ID);
        String eventId = "project-soft-delete-event";
        String json = "{\"providerEventId\":\"" + eventId + "\",\"provider\":\"" + PROVIDER
                + "\",\"eventType\":\"project.changed\",\"signatureResult\":\"VALID\","
                + "\"canonicalPayload\":{\"publicProjectId\":\"" + projectPublicId
                + "\",\"publicCustomerId\":\"" + customerPublicId + "\",\"status\":\"ACTIVE\"}}";
        InboundEventService.Receipt receipt = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, eventId, HASH_2, NOW,
                ExternalDtoSnapshot.ofAllowList(json, ExternalDtoSnapshot.INBOUND_FIELDS), true, NOW);
        InboundEvent claimed = claimEvent(receipt.event().getId());
        assertTrue(completeEvent(claimed, IntegrationHubStates.INBOUND_DLQ, "INBOUND_PROCESSING_FAILED"));
        Authentication admin = adminAuthentication();
        SecurityContextHolder.getContext().setAuthentication(admin);
        var replay = inboundEventAdminService.replay(receipt.event().getAdminReference(),
                "INCIDENT_RECOVERY", admin, NOW);

        jdbcTemplate.update("UPDATE t_project SET deleted_flag = 1 WHERE id = ?", RESOURCE_PROJECT_ID);
        var result = inboundEventAdminService.processReplay(replay.replayReference(), admin, NOW);

        assertEquals("REJECTED", result.status());
        assertEquals("CURRENT_SCOPE_INVALID", result.resultCode());
    }

    @Test
    void adminProjectionはopaqueReferenceだけを返し内部IDを返さない() {
        insertBinding();
        InboundEvent event = insertDlqEvent();

        var page = inboundEventAdminService.page(1, 25, "DLQ", PROVIDER);
        var dto = page.records().stream().filter(row -> row.providerEventId().equals(EVENT_ID)).findFirst().orElseThrow();

        assertTrue(dto.reference().matches("[A-Za-z0-9_-]{43}"));
        assertFalse(dto.reference().equals(Long.toString(event.getId())));
    }

    private void insertResourceFixtures() {
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name, deleted_flag) VALUES (?, ?, 0)",
                RESOURCE_CUSTOMER_ID, "b2-resource-customer");
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name, deleted_flag) VALUES (?, ?, 0)",
                RESOURCE_REPARENT_CUSTOMER_ID, "b2-reparent-customer");
        jdbcTemplate.update("INSERT INTO t_project (id, project_name, customer_id, status, deleted_flag) "
                + "VALUES (?, ?, ?, '募集中', 0)", RESOURCE_PROJECT_ID, "b2-resource-project", RESOURCE_CUSTOMER_ID);
    }

    private long clientDbId() {
        return jdbcTemplate.queryForObject("SELECT id FROM m_api_client WHERE client_id = ?", Long.class, CLIENT_ID);
    }

    private ExternalDtoSnapshot snapshot() {
        return ExternalDtoSnapshot.ofAllowList(
                "{\"providerEventId\":\"" + EVENT_ID + "\",\"provider\":\"" + PROVIDER
                        + "\",\"eventType\":\"health.ping\",\"signatureResult\":\"VALID\"}",
                ExternalDtoSnapshot.INBOUND_FIELDS);
    }

    private long insertBinding() {
        return insertBinding("health.ping");
    }

    private InboundEvent claimEvent(Long id) {
        return inboundEventService.claim(id, LEASE_TOKEN, NOW, NOW.plusMinutes(5));
    }

    private boolean completeEvent(InboundEvent claimed, String status, String resultCode) {
        return inboundEventService.complete(claimed.getId(), claimed.getVersion(), LEASE_TOKEN,
                status, resultCode, NOW);
    }

    private long insertBinding(String eventType) {
        String scope = "{\"tenantIds\":[\"" + TENANT + "\"],\"legalEntityIds\":[\""
                + LEGAL_ENTITY + "\"],\"projectIds\":[\"" + RESOURCE_PROJECT_ID
                + "\"],\"customerIds\":[\"" + RESOURCE_CUSTOMER_ID + "\"]}";
        jdbcTemplate.update("INSERT INTO m_api_client (client_id, owner_ref, tenant_id, legal_entity_id, "
                        + "data_scope_json, allowed_cidrs, client_tier, status, version) VALUES "
                        + "(?, 'PROJECT_OWNER', ?, ?, ?, '127.0.0.1/32', 'INTERNAL_TEST', 'ACTIVE', 0)",
                CLIENT_ID, TENANT, LEGAL_ENTITY, scope);
        long id = jdbcTemplate.queryForObject("SELECT id FROM m_api_client WHERE client_id = ?", Long.class, CLIENT_ID);
        String receiveScope = scope;
        jdbcTemplate.update("INSERT INTO m_api_client_scope (api_client_id, scope_code, operation_code, "
                        + "data_scope_json, status, version) VALUES (?, 'integration.webhook.receive', "
                        + "'integration.webhook.receive', ?, 'ACTIVE', 0)", id, receiveScope);
        jdbcTemplate.update("INSERT INTO m_webhook_subscription (client_id, provider_name, direction, event_type, endpoint_url, "
                        + "key_id, encrypted_signing_secret, crypto_key_version, data_scope_json, status, version) "
                        + "VALUES (?, 'provider-b2', 'INBOUND', ?, 'https://unused.invalid/inbound', 'key-b2', "
                        + "'IHG1:v1:iv:cipher', 'v1', ?, 'ACTIVE', 0)", CLIENT_ID, eventType, receiveScope);
        return id;
    }

    private InboundEvent insertDlqEvent() {
        InboundEventService.Receipt receipt = inboundEventService.recordReceived(
                CLIENT_ID, PROVIDER, EVENT_ID, HASH_1, NOW, snapshot(), true, NOW);
        InboundEvent claimed = claimEvent(receipt.event().getId());
        assertTrue(completeEvent(claimed, IntegrationHubStates.INBOUND_DLQ, "INBOUND_PROCESSING_FAILED"));
        return InboundEvent.builder().id(claimed.getId()).status(IntegrationHubStates.INBOUND_DLQ).build();
    }

    private Authentication adminAuthentication() {
        SysUser user = SysUser.builder().username("b2-admin").role("管理者").status(1).build();
        user.setId(7001L);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者"))), "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
    }
}
