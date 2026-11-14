package com.ses.service.ai;

import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF08 N8-2: 実MySQL（InnoDB・Flyway適用済みschema・CHECK制約）で、外部送信監査がREQUIRES_NEWで独立commitされ、
 * 外側transactionのrollback後も独立connectionから読めることを確認する。列長上限の値もstrict modeで受理されることを見る。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class AiExternalSendAuditMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    private static final String KEY_PREFIX = "nf08-mysql-audit-";

    @Autowired private AiExternalSendAuditor auditor;
    @Autowired private AuditLogService auditLogService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM t_audit_log WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
        SecurityContextHolder.clearContext();
    }

    @Test
    void 外側transactionのrollback後も送信前監査は独立connectionから読める() throws Exception {
        assertTrue(AopUtils.isAopProxy(auditLogService), "REQUIRES_NEWはSpring proxy経由で成立する");
        String attemptKey = KEY_PREFIX + "attempt-" + UUID.randomUUID();
        String controlKey = KEY_PREFIX + "control-" + UUID.randomUUID();

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        outer.executeWithoutResult(status -> {
            auditor.recordAttempt(audit(attemptKey));
            auditLogService.recordDomainEventRequired(controlEntry(controlKey));
            status.setRollbackOnly();
        });

        assertEquals(1, countIndependently(attemptKey), "ATTEMPTは独立にcommit済み");
        assertEquals(0, countIndependently(controlKey), "既存methodは外側transactionと一緒にrollbackされる");
    }

    @Test
    void 上限長の監査値は列長とCHECK制約に収まりstrict_modeで受理される() throws Exception {
        String resultKey = KEY_PREFIX + "result-" + UUID.randomUUID();
        String deniedKey = KEY_PREFIX + "denied-" + UUID.randomUUID();

        auditor.recordResult(longAudit(resultKey, "COPILOT_PROVIDER_429", false, 502));
        auditor.recordDenied(longAudit(deniedKey, "AI_EXTERNAL_SEND_UNMASKED_PII", false, 400));

        assertEquals(1, countIndependently(resultKey));
        assertEquals(1, countIndependently(deniedKey));
    }

    private int countIndependently(String idempotencyKey) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_audit_log WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static AuditLog controlEntry(String idempotencyKey) {
        AuditLog entry = new AuditLog();
        entry.setUsername("SYSTEM");
        entry.setMethod("TEST");
        entry.setUri("nf08-mysql-control");
        entry.setStatus(200);
        entry.setApplicationCode("NF08_MYSQL_CONTROL");
        entry.setSuccessFlag(true);
        entry.setActorType(ActorType.SYSTEM.name());
        entry.setConfirmationSource(ConfirmationSource.SCHEDULER_POLL.name());
        entry.setIdempotencyKey(idempotencyKey);
        return entry;
    }

    private static AiExternalSendAuditor.SendAudit audit(String attemptId) {
        return new AiExternalSendAuditor.SendAudit(attemptId, "trace-" + attemptId,
                AiExternalSendApprovalFixtures.DECISION_ID, "a".repeat(64), "gemini",
                AiExternalSendApprovalFixtures.MODEL, AiGatewayRequest.USE_COPILOT, AiExternalSendApprovalFixtures.HOST,
                "tenant-a", 1L, "dashboard.summary", 7L, "f".repeat(64), 1234, 6, 6L, 6L, "NOT_STARTED",
                "APPROVED_UPPER_BOUND", null, 6, "UPPER_BOUND", 202, true, null);
    }

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
