package com.ses.service.ai;

import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * NF08 外部送信の監査。既存の t_audit_log（{@link AuditLogService#recordDomainEventRequired}）へ記録し、DDLを追加しない。
 * <p>記録するのは決定ID・provider・model・用途・宛先host・prompt byte数・token数・費用・結果コード・呼出主体だけである。
 * prompt原文、allow-list値、PII、API key、provider応答本文は記録しない。全ての値は固定文字集合へ正規化し、
 * 列長（before/after_state 255文字等）を超えないよう切り詰める。</p>
 */
@Slf4j
@Component
public class AiExternalSendAuditor {

    public static final String APPLICATION_ATTEMPT = "AI_EXTERNAL_SEND_ATTEMPT";
    public static final String APPLICATION_RESULT = "AI_EXTERNAL_SEND_RESULT";
    public static final String APPLICATION_DENIED = "AI_EXTERNAL_SEND_DENIED";
    public static final String REFERENCE_TYPE = "AI_EXTERNAL_SEND_DECISION";
    public static final String METHOD = "AI_SEND";
    public static final String ERROR_CATEGORY = "AI_EXTERNAL_SEND";

    private static final int STATE_MAX = 255;

    private final AuditLogService auditLogService;

    public AiExternalSendAuditor(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 監査先のない構成。外部送信は送信前監査で必ず失敗する（local providerには影響しない）。 */
    public static AiExternalSendAuditor unavailable() {
        return new AiExternalSendAuditor(null);
    }

    /** 送信前の必須監査。永続化できなければ呼出元は送信してはならない。 */
    public void recordAttempt(SendAudit audit) {
        persistRequired(toEntry(APPLICATION_ATTEMPT, audit, 202, true, attemptState(audit)));
    }

    /** 送信後（成功・失敗）の必須監査。 */
    public void recordResult(SendAudit audit) {
        persistRequired(toEntry(APPLICATION_RESULT, audit, audit.status(), audit.succeeded(), resultState(audit)));
    }

    /** 承認済み経路での送信前拒否。best effortで記録し、失敗しても拒否結果は変えない。 */
    public void recordDenied(SendAudit audit) {
        try {
            persistRequired(toEntry(APPLICATION_DENIED, audit, audit.status(), false, resultState(audit)));
        } catch (RuntimeException ex) {
            log.warn("AI外部送信の拒否監査を記録できません: reason={} errorType={}",
                    code(audit.reasonCode()), ex.getClass().getSimpleName());
        }
    }

    AuditLog toEntry(String applicationCode, SendAudit audit, int status, boolean success, String afterState) {
        ActorAttribution actor = ExecutionActorContext.resolve();
        String username = SecurityUtils.currentUsername();
        AuditLog entry = new AuditLog();
        entry.setUsername(clip(username == null || username.isBlank() ? actor.actorType().name() : username, 50));
        entry.setMethod(METHOD);
        entry.setUri(clip("ai-external-send://" + code(audit.destinationHost()) + "/" + code(audit.purpose()), 500));
        entry.setStatus(status);
        entry.setApplicationCode(applicationCode);
        entry.setSuccessFlag(success);
        entry.setReferenceType(REFERENCE_TYPE);
        entry.setActorType(actor.actorType().name());
        entry.setConfirmationSource(actor.confirmationSource().name());
        entry.setHumanUserId(actor.humanUserId());
        entry.setBeforeState(bindingState(audit));
        entry.setAfterState(clip(afterState, STATE_MAX));
        entry.setCorrelationId(clip(code(audit.traceId()), 128));
        entry.setIdempotencyKey(clip(code(audit.attemptId()), 128));
        if (!success) {
            entry.setErrorCode(clip(code(audit.reasonCode()), 64));
            entry.setErrorCategory(ERROR_CATEGORY);
        }
        return entry;
    }

    /** 承認束縛（決定ID・用途・provider・model）。 */
    static String bindingState(SendAudit audit) {
        return clip("decision=" + code(audit.decisionId())
                + ";purpose=" + code(audit.purpose())
                + ";provider=" + code(audit.provider())
                + ";model=" + code(audit.model()), STATE_MAX);
    }

    /** 送信前の予約状態（送信byte数・上限見積り・予約後累計）。 */
    static String attemptState(SendAudit audit) {
        return clip("bytes=" + audit.promptBytes()
                + ";upperYen=" + number(audit.upperBoundYen())
                + ";dayYen=" + number(audit.dayTotalYen())
                + ";monthYen=" + number(audit.monthTotalYen()), STATE_MAX);
    }

    /** 結果状態（dispatch状態・理由・byte数・token数・費用・所要時間）。 */
    static String resultState(SendAudit audit) {
        CopilotProviderUsage usage = audit.usage();
        return clip("outcome=" + code(audit.outcome())
                + ";reason=" + code(audit.reasonCode())
                + ";bytes=" + audit.promptBytes()
                + ";inTok=" + (usage == null ? "-" : usage.promptTokenCount())
                + ";outTok=" + (usage == null ? "-" : usage.candidateTokenCount())
                + ";thinkTok=" + (usage == null ? "-" : number(usage.thoughtsTokenCount()))
                + ";totTok=" + (usage == null ? "-" : usage.totalTokenCount())
                + ";yen=" + number(audit.costYen())
                + ";yenState=" + code(audit.costState())
                + ";ms=" + number(audit.latencyMs()), STATE_MAX);
    }

    private void persistRequired(AuditLog entry) {
        if (auditLogService == null) {
            throw new IllegalStateException("AI外部送信監査の永続化先がありません");
        }
        auditLogService.recordDomainEventRequired(entry);
    }

    /** 自由文やPIIが混入しないよう、識別子用の固定文字集合だけを残す。 */
    static String code(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.replaceAll("[^A-Za-z0-9._:/-]", "");
        return normalized.isEmpty() ? "-" : clip(normalized, 128);
    }

    private static String number(Number value) {
        return value == null ? "-" : value.toString();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 1行分の監査値。prompt原文・allow-list値・応答本文を保持するfieldを持たない。
     * status/succeededは結果・拒否の記録に使い、attemptでは無視する。
     */
    public record SendAudit(String attemptId, String traceId, String decisionId, String provider, String model,
                            String purpose, String destinationHost, int promptBytes, Integer upperBoundYen,
                            Long dayTotalYen, Long monthTotalYen, String outcome, String reasonCode,
                            CopilotProviderUsage usage, Integer costYen, String costState, int status,
                            boolean succeeded, Long latencyMs) {
    }
}
