package com.ses.service.ai;

import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import com.ses.service.impl.AuditLogServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF08 N8-2: 外部送信の監査（ATTEMPT/RESULT/DENIED）を実H2へinsertし、外側transactionがrollbackしても
 * REQUIRES_NEW で記録が残ることを、Spring proxy経由の実serviceで確認する。
 * <p>テストclassは {@code @Transactional}（テストtransactionはrollback）。外側の業務transactionは
 * REQUIRES_NEW のTransactionTemplateで別に開始し、明示的にrollbackさせる。独立transactionでcommitされた行は
 * テストtransactionのrollbackでは消えないため、テスト固有のidempotency_key接頭辞の行を{@code @AfterEach}で
 * 独立transactionからDELETE FROMで削除する（他テストの行は読まない・消さない）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiExternalSendAuditorH2PersistenceTest {

    private static final String KEY_PREFIX = "nf08-h2-audit-";
    private static final String DIGEST = "a467bc9955f3827dfd7a8465a560562c8221e792bbee578c1aa4cccd519a6e6c";

    @Autowired
    private AiExternalSendAuditor auditor;
    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanupCommittedAuditRows() {
        requiresNew().executeWithoutResult(status -> jdbcTemplate.update(
                "DELETE FROM t_audit_log WHERE idempotency_key LIKE ?", KEY_PREFIX + "%"));
        SecurityContextHolder.clearContext();
    }

    @Test
    void 監査serviceはSpring_proxyでありREQUIRES_NEWの経路を使う() {
        assertTrue(auditLogService instanceof AuditLogServiceImpl, "AuditLogServiceの実体は既存のImpl");
        assertTrue(AopUtils.isAopProxy(auditLogService), "新methodのREQUIRES_NEWはproxy経由でだけ成立する");
    }

    @Test
    void 外側transactionがrollbackしても送信前監査は独立transactionで残る() {
        String attemptKey = KEY_PREFIX + "attempt-" + UUID.randomUUID();
        String controlKey = KEY_PREFIX + "control-" + UUID.randomUUID();

        requiresNew().executeWithoutResult(outer -> {
            auditor.recordAttempt(audit(attemptKey, "APPROVED_UPPER_BOUND", true, 202));
            // 対照: 既存の必須監査は呼出側transactionへ参加するため、外側のrollbackで一緒に消える。
            auditLogService.recordDomainEventRequired(controlEntry(controlKey));
            outer.setRollbackOnly();
        });

        assertEquals(1, count(attemptKey), "ATTEMPTは外側rollback後も残る");
        assertEquals(0, count(controlKey), "既存methodの意味（呼出側transactionへの参加）は変わらない");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT application_code, method, uri, before_state, after_state, actor_type, confirmation_source,"
                        + " success_flag, status FROM t_audit_log WHERE idempotency_key = ?", attemptKey);
        assertEquals(AiExternalSendAuditor.APPLICATION_ATTEMPT, row.get("APPLICATION_CODE"));
        assertEquals(AiExternalSendAuditor.METHOD, row.get("METHOD"));
        assertTrue(String.valueOf(row.get("URI")).contains("&tenant=tenant-a&le=1&query=dashboard.summary"));
        assertTrue(String.valueOf(row.get("BEFORE_STATE")).contains(";digest=" + DIGEST + ";"));
        assertEquals("SYSTEM", row.get("ACTOR_TYPE"));
        assertEquals("SCHEDULER_POLL", row.get("CONFIRMATION_SOURCE"));
        assertEquals(202, ((Number) row.get("STATUS")).intValue());
    }

    @Test
    void 結果監査と拒否監査も外側rollbackから独立して残り列長と主体制約に収まる() {
        String resultKey = KEY_PREFIX + "result-" + UUID.randomUUID();
        String deniedKey = KEY_PREFIX + "denied-" + UUID.randomUUID();

        requiresNew().executeWithoutResult(outer -> {
            auditor.recordResult(longAudit(resultKey, "COPILOT_PROVIDER_429", false, 502));
            auditor.recordDenied(longAudit(deniedKey, "AI_EXTERNAL_SEND_DAILY_COST_LIMIT", false, 429));
            outer.setRollbackOnly();
        });

        assertEquals(1, count(resultKey));
        assertEquals(1, count(deniedKey), "拒否監査もbest effortで独立して残す");
        Map<String, Object> result = jdbcTemplate.queryForMap(
                "SELECT application_code, error_code, error_category, success_flag FROM t_audit_log"
                        + " WHERE idempotency_key = ?", resultKey);
        assertEquals(AiExternalSendAuditor.APPLICATION_RESULT, result.get("APPLICATION_CODE"));
        assertEquals("COPILOT_PROVIDER_429", result.get("ERROR_CODE"));
        assertEquals(AiExternalSendAuditor.ERROR_CATEGORY, result.get("ERROR_CATEGORY"));
        assertFalse((Boolean) result.get("SUCCESS_FLAG"));
    }

    @Test
    void proxyを通らない呼出しでは独立transactionを保証できず記録しない() {
        AuditLogServiceImpl target = (AuditLogServiceImpl) org.springframework.test.util.AopTestUtils
                .getTargetObject(auditLogService);
        String key = KEY_PREFIX + "direct-" + UUID.randomUUID();
        AuditLog entry = controlEntry(key);

        requiresNew().executeWithoutResult(outer -> assertThrows(IllegalStateException.class,
                () -> target.recordDomainEventRequiredInNewTransaction(entry),
                "外側transactionへ参加してしまう自己呼出し・手動呼出しは失敗させる"));
        assertThrows(IllegalStateException.class, () -> target.recordDomainEventRequiredInNewTransaction(entry));

        assertEquals(0, count(key));
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private int count(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE idempotency_key = ?", Integer.class, idempotencyKey);
        return count == null ? 0 : count;
    }

    private static AuditLog controlEntry(String idempotencyKey) {
        AuditLog entry = new AuditLog();
        entry.setUsername("SYSTEM");
        entry.setMethod("TEST");
        entry.setUri("nf08-h2-control");
        entry.setStatus(200);
        entry.setApplicationCode("NF08_H2_CONTROL");
        entry.setSuccessFlag(true);
        entry.setActorType(ActorType.SYSTEM.name());
        entry.setConfirmationSource(ConfirmationSource.SCHEDULER_POLL.name());
        entry.setIdempotencyKey(idempotencyKey);
        return entry;
    }

    private static AiExternalSendAuditor.SendAudit audit(String attemptId, String reason, boolean succeeded,
                                                         int status) {
        return new AiExternalSendAuditor.SendAudit(attemptId, "trace-" + attemptId,
                AiExternalSendApprovalFixtures.DECISION_ID, DIGEST, "gemini", AiExternalSendApprovalFixtures.MODEL,
                AiGatewayRequest.USE_COPILOT, AiExternalSendApprovalFixtures.HOST, "tenant-a", 1L,
                "dashboard.summary", 7L, "f".repeat(64), 1234, 6, 6L, 6L, "NOT_STARTED", reason, null, 6,
                "UPPER_BOUND", status, succeeded, null);
    }

    /** 各識別子が上限長の監査値（列長・CHECK制約に収まることを実DBで確認する）。 */
    private static AiExternalSendAuditor.SendAudit longAudit(String attemptId, String reason, boolean succeeded,
                                                             int status) {
        return new AiExternalSendAuditor.SendAudit(attemptId, "t".repeat(200),
                "D".repeat(64), "e".repeat(64), "gemini", "m".repeat(64), AiGatewayRequest.USE_COPILOT,
                AiExternalSendApprovalFixtures.HOST, "a".repeat(200), Long.MAX_VALUE, "q".repeat(200),
                Long.MAX_VALUE, "c".repeat(200), Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, "MAY_HAVE_BEEN_SENT", reason,
                new CopilotProviderUsage(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE),
                Integer.MAX_VALUE, "ACTUAL_OVER_BOUND", status, succeeded, Long.MAX_VALUE);
    }
}
