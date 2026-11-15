package com.ses.config.integrationhub;

import com.ses.service.integrationhub.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalApiAuditRetentionH2Test {
    @Autowired ExternalApiAuditService audit;
    @Autowired ApiRetentionPurgeService purge;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiDeliveryService deliveryService;
    @Autowired com.ses.mapper.ApiDeliveryMapper deliveryMapper;

    @Test
    void 監査はUTCの一年保存と保留を守り件数上限付きで削除する() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        String prefix = "audit-retention-" + UUID.randomUUID();
        long held = create(prefix + "-held"), expired = create(prefix + "-expired"), fresh = create(prefix + "-fresh");
        var created = jdbc.queryForObject("SELECT created_at FROM t_external_api_audit WHERE id = ?",
                java.sql.Timestamp.class, fresh).toLocalDateTime();
        var expiry = jdbc.queryForObject("SELECT retention_expires_at FROM t_external_api_audit WHERE id = ?",
                java.sql.Timestamp.class, fresh).toLocalDateTime();
        assertThat(created).isAfterOrEqualTo(before.minusSeconds(1)).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        assertThat(expiry).isEqualTo(created.plusYears(1));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE t_external_api_audit SET retention_expires_at = ? WHERE id IN (?,?)", now.minusDays(1), held, expired);
        assertThat(purge.acquireHold("EXTERNAL_AUDIT", held, "LEGAL_HOLD", now)).isTrue();
        assertThat(purge.purgeExpired("EXTERNAL_AUDIT", IntegrationHubStates.RETENTION_AUDIT_1Y, now, 1).purged()).isEqualTo(1);
        assertThat(count(held)).isEqualTo(1);
        assertThat(count(expired)).isZero();
        assertThat(count(fresh)).isEqualTo(1);
        assertThat(purge.releaseHold("EXTERNAL_AUDIT", held, now)).isTrue();
        assertThat(purge.purgeExpired("EXTERNAL_AUDIT", IntegrationHubStates.RETENTION_AUDIT_1Y, now, 1).purged()).isEqualTo(1);
        assertThat(count(held)).isZero();
    }

    @Test
    void 未送信のattemptだけ結果CASで返却し不確定timeoutとkillSwitchは別扱いにする() {
        String client = "attempt-" + UUID.randomUUID();
        jdbc.update("INSERT INTO m_webhook_subscription(client_id,direction,event_type,endpoint_url,key_id,"
                + "encrypted_signing_secret,crypto_key_version,data_scope_json) "
                + "VALUES(?,'OUTBOUND','resource.changed','https://web.example:443/hook','key-1','synthetic','v1','{}')", client);
        long subscription = jdbc.queryForObject("SELECT id FROM m_webhook_subscription WHERE client_id=?", Long.class, client);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (String code : java.util.List.of("TRANSPORT_BUSY", "TIMEOUT")) {
            String event = client + code;
            String key = IntegrationHubDigest.sha256Hex(event), hash = IntegrationHubDigest.sha256Hex("{}");
            jdbc.update("INSERT INTO t_api_delivery(event_id,subscription_id,delivery_generation,client_id,scope_code,"
                    + "tenant_id,scope_digest,event_type,schema_version,provider_idempotency_key,external_dto_snapshot,"
                    + "payload_hash,status,attempt_count,lease_token,lease_expires_at,version,next_attempt_at) "
                    + "VALUES(?,?,1,?,'project','tenant-a',?,'resource.changed','v1',?,'{}',?,'CLAIMED',8,'lease',?,1,?)",
                    event, subscription, client, "a".repeat(64), key, hash, now.plusMinutes(1), now);
            long id = jdbc.queryForObject("SELECT id FROM t_api_delivery WHERE event_id=?", Long.class, event);
            assertThat(deliveryService.markRetryable(id, 1, 1, "lease", key, hash, code, now, now.plusMinutes(1))).isTrue();
            var row = deliveryMapper.selectById(id);
            assertThat(row.getAttemptCount()).isEqualTo(code.equals("TRANSPORT_BUSY") ? 7 : 8);
            assertThat(row.getStatus()).isEqualTo(code.equals("TRANSPORT_BUSY") ? "RETRYABLE" : "DLQ");
            assertThat(deliveryService.markRetryable(id, 1, 1, "lease", key, hash, code, now, now.plusMinutes(1))).isFalse();
        }
        jdbc.update("UPDATE t_api_delivery SET status='CLAIMED',lease_token='stale',lease_expires_at=?,terminal_at=NULL,last_error_code=NULL,retention_class=NULL,retention_expires_at=NULL WHERE event_id=?",
                now.minusSeconds(1), client + "TIMEOUT");
        assertThat(deliveryService.recoverExpiredLeases(now)).isGreaterThanOrEqualTo(1);
        var recovered = jdbc.queryForMap("SELECT status,last_error_code,terminal_at FROM t_api_delivery WHERE event_id=?", client + "TIMEOUT");
        assertThat(recovered.get("STATUS")).isEqualTo("DLQ");
        assertThat(recovered.get("LAST_ERROR_CODE")).isEqualTo("MAX_ATTEMPTS");
        assertThat(((java.sql.Timestamp) recovered.get("TERMINAL_AT")).toLocalDateTime()).isBetween(now.minusSeconds(1), now.plusSeconds(1));
        String event = client + "hold";
        jdbc.update("INSERT INTO t_api_delivery(event_id,subscription_id,client_id,scope_code,tenant_id,scope_digest,"
                + "event_type,schema_version,provider_idempotency_key,external_dto_snapshot,payload_hash,status,next_attempt_at) "
                + "VALUES(?, ?, ?, 'project','tenant-a',?,'resource.changed','v1',?,'{}',?,'PENDING',?)",
                event, subscription, client, "a".repeat(64), IntegrationHubDigest.sha256Hex(event), "b".repeat(64), now);
        long id = jdbc.queryForObject("SELECT id FROM t_api_delivery WHERE event_id=?", Long.class, event);
        assertThat(deliveryMapper.holdPending(id, 0, now)).isEqualTo(1);
        assertThat(deliveryMapper.selectById(id).getLastErrorCode()).isEqualTo("KILL_SWITCH");
        assertThat(deliveryMapper.selectById(id).getAttemptCount()).isZero();
    }

    private long create(String correlation) {
        audit.recordRequired(new ExternalApiAuditRecord("UNAUTHENTICATED", "NONE", null, null, null, correlation,
                "GET", "/external-api/v1/projects", "AUTHENTICATED", "ALLOWED", "ALLOWED", "READ_ALLOWED",
                "ALLOWED", 200, "SUCCESS", true));
        return jdbc.queryForObject("SELECT id FROM t_external_api_audit WHERE correlation_id = ?", Long.class, correlation);
    }

    private int count(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_external_api_audit WHERE id = ?", Integer.class, id);
    }
}
