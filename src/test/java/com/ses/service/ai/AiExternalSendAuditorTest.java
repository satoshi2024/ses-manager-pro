package com.ses.service.ai;

import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import com.ses.service.impl.AuditLogServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * NF08: 外部送信監査は既存t_audit_logへ必須記録し、prompt原文・PIIを含めない。
 * 実DBへのinsertと外側rollback後の残存は {@code AiExternalSendAuditorH2PersistenceTest} で確認する。
 */
class AiExternalSendAuditorTest {

    private static final String DIGEST = "a467bc9955f3827dfd7a8465a560562c8221e792bbee578c1aa4cccd519a6e6c";
    private static final String CONFIG_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void clearSecurityContext() {
        // 他testの認証状態に依存せず、主体解決をsystem主体へ固定する。
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 送信前監査は決定ID_digest_model_用途_送信範囲_byte数を固定書式で記録する() {
        AuditLogService service = mock(AuditLogService.class);
        new AiExternalSendAuditor(service).recordAttempt(audit("APPROVED_UPPER_BOUND", null, true, 202));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(service).recordDomainEventRequired(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals(AiExternalSendAuditor.APPLICATION_ATTEMPT, entry.getApplicationCode());
        assertEquals(AiExternalSendAuditor.METHOD, entry.getMethod());
        assertEquals("ai-external-send://generativelanguage.googleapis.com/MANAGEMENT_COPILOT"
                + "?provider=gemini&tenant=tenant-a&le=1&query=dashboard.summary&artifact=7&cfg=" + CONFIG_HASH,
                entry.getUri());
        assertEquals(AiExternalSendAuditor.REFERENCE_TYPE, entry.getReferenceType());
        assertEquals("decision=NF08-EXT-2026-0001;digest=" + DIGEST + ";purpose=MANAGEMENT_COPILOT;"
                + "model=gemini-synthetic-model", entry.getBeforeState());
        assertEquals("bytes=1234;upperYen=6;dayYen=6;monthYen=6", entry.getAfterState());
        assertEquals("trace-1", entry.getCorrelationId());
        assertEquals("attempt-1", entry.getIdempotencyKey());
        assertEquals(202, entry.getStatus());
        assertTrue(entry.getSuccessFlag());
        // 認証主体がないテストでは安全なsystem主体へ解決する（DB制約の主体/チャネル組に適合）
        assertEquals("SYSTEM", entry.getActorType());
        assertEquals("SCHEDULER_POLL", entry.getConfirmationSource());
        assertNull(entry.getHumanUserId());
        assertNull(entry.getErrorCode());
    }

    @Test
    void 結果監査はtoken数_費用_結果を記録し失敗時は理由をerrorCodeへ置く() {
        AuditLogService service = mock(AuditLogService.class);
        AiExternalSendAuditor auditor = new AiExternalSendAuditor(service);
        auditor.recordResult(audit("SUCCEEDED", new CopilotProviderUsage(120, 30, 150, 0, 0), true, 200));
        auditor.recordResult(audit("COPILOT_PROVIDER_429", null, false, 502));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(service, times(2)).recordDomainEventRequired(captor.capture());
        List<AuditLog> entries = captor.getAllValues();
        AuditLog success = entries.get(0);
        assertEquals(AiExternalSendAuditor.APPLICATION_RESULT, success.getApplicationCode());
        assertEquals("outcome=RESPONSE_RECEIVED;reason=SUCCEEDED;bytes=1234;inTok=120;outTok=30;thinkTok=0;"
                + "totTok=150;yen=1;yenState=ACTUAL;ms=42", success.getAfterState());
        assertTrue(success.getSuccessFlag());
        AuditLog failure = entries.get(1);
        assertFalse(failure.getSuccessFlag());
        assertEquals(502, failure.getStatus());
        assertEquals("COPILOT_PROVIDER_429", failure.getErrorCode());
        assertEquals(AiExternalSendAuditor.ERROR_CATEGORY, failure.getErrorCategory());
        assertTrue(failure.getAfterState().contains("inTok=-"));
    }

    @Test
    void 自由文やPIIらしき値は固定文字集合へ正規化し列長を超えない() {
        AuditLogService service = mock(AuditLogService.class);
        AiExternalSendAuditor.SendAudit hostile = new AiExternalSendAuditor.SendAudit(
                "attempt 1 ヤマダ タロウ", "trace\n<script>", "NF08-EXT-2026-0001 tel:090-1234-5678",
                "x".repeat(300), "gemini", "model\"; DROP TABLE x;--", "MANAGEMENT_COPILOT", "host name.example",
                "tenant 株式会社サンプル " + "t".repeat(300), Long.MAX_VALUE, "q".repeat(300), Long.MIN_VALUE,
                "c".repeat(300), 10, 1, 1L, 1L, "RESPONSE_RECEIVED", "x".repeat(400), null, 1, "ACTUAL", 200,
                true, 1L);

        new AiExternalSendAuditor(service).recordResult(hostile);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(service).recordDomainEventRequired(captor.capture());
        AuditLog entry = captor.getValue();
        String all = String.join("|", entry.getUri(), entry.getBeforeState(), entry.getAfterState(),
                entry.getCorrelationId(), entry.getIdempotencyKey());
        assertFalse(all.contains("ヤマダ"));
        assertFalse(all.contains("株式会社"));
        assertFalse(all.contains(" "));
        assertFalse(all.contains("<"));
        assertFalse(all.contains("\n"));
        assertFalse(all.contains("\""));
        assertTrue(entry.getBeforeState().length() <= 255);
        assertTrue(entry.getAfterState().length() <= 255);
        assertTrue(entry.getUri().length() <= 500);
        assertTrue(entry.getUri().contains("&cfg="), "各識別子を64文字に切り詰め、末尾のconfig hashまでuri列に収める");
    }

    @Test
    void 監査先がない構成では送信前監査が失敗する() {
        AiExternalSendAuditor auditor = AiExternalSendAuditor.unavailable();

        assertThrows(IllegalStateException.class,
                () -> auditor.recordAttempt(audit("APPROVED_UPPER_BOUND", null, true, 202)));
        assertThrows(IllegalStateException.class,
                () -> auditor.recordResult(audit("SUCCEEDED", null, true, 200)));
    }

    @Test
    void 必須監査の永続化失敗は呼出元へ伝播し拒否監査はbestEffortにする() {
        AuditLogService service = mock(AuditLogService.class);
        doThrow(new IllegalStateException("db down")).when(service).recordDomainEventRequired(any());
        AiExternalSendAuditor auditor = new AiExternalSendAuditor(service);

        assertThrows(IllegalStateException.class,
                () -> auditor.recordAttempt(audit("APPROVED_UPPER_BOUND", null, true, 202)));
        assertDoesNotThrow(() -> auditor.recordDenied(audit("AI_EXTERNAL_SEND_DAILY_COST_LIMIT", null, false, 429)));
    }

    @Test
    void 外側transaction内で独立transactionを使えない監査先は記録せず失敗させる() {
        AuditLogService service = mock(AuditLogService.class);
        AiExternalSendAuditor auditor = new AiExternalSendAuditor(service);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class,
                () -> auditor.recordAttempt(audit("APPROVED_UPPER_BOUND", null, true, 202)),
                "外側rollbackで監査だけが消える経路では送信前監査を成立させない");
        assertThrows(IllegalStateException.class,
                () -> auditor.recordResult(audit("SUCCEEDED", null, true, 200)));
        assertDoesNotThrow(() -> auditor.recordDenied(audit("AI_EXTERNAL_SEND_DAILY_COST_LIMIT", null, false, 429)));
        verifyNoInteractions(service);
    }

    @Test
    void Spring_proxyを通らない監査serviceは独立transactionとして扱わない() {
        // 手動構築・mockのAuditLogServiceImplはAOP proxyではないため、REQUIRES_NEWのmethodを呼ばない。
        AuditLogServiceImpl service = mock(AuditLogServiceImpl.class);
        AiExternalSendAuditor auditor = new AiExternalSendAuditor(service);

        auditor.recordAttempt(audit("APPROVED_UPPER_BOUND", null, true, 202));

        verify(service, never()).recordDomainEventRequiredInNewTransaction(any());
        verify(service).recordDomainEventRequired(any());
    }

    private static AiExternalSendAuditor.SendAudit audit(String reason, CopilotProviderUsage usage,
                                                         boolean succeeded, int status) {
        return new AiExternalSendAuditor.SendAudit("attempt-1", "trace-1", AiExternalSendApprovalFixtures.DECISION_ID,
                DIGEST, "gemini", AiExternalSendApprovalFixtures.MODEL, AiGatewayRequest.USE_COPILOT,
                AiExternalSendApprovalFixtures.HOST, "tenant-a", 1L, "dashboard.summary", 7L, CONFIG_HASH,
                1234, 6, 6L, 6L, "RESPONSE_RECEIVED", reason, usage,
                usage == null ? null : 1, usage == null ? "UPPER_BOUND" : "ACTUAL", status, succeeded, 42L);
    }
}
