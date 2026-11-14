package com.ses.service.ai;

import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import com.ses.service.impl.AuditLogServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * NF08 外部送信の監査。既存の t_audit_log へ記録し、DDLを追加しない。
 * <p>記録するのは決定ID・承認digest・provider・model・用途・宛先host・tenant・法人・queryId・artifact・config hash・
 * prompt byte数・token数・費用・結果コード・呼出主体だけである。prompt原文、allow-list値、PII、API key、
 * provider応答本文は記録しない。全ての識別子は固定文字集合へ正規化し、列長（before/after_state 255文字、
 * uri 500文字等）を超えないよう切り詰める。</p>
 * <p><b>永続性</b>: 監査は呼出側transactionの成否から独立していなければならない（送信した事実は、外側の業務処理が
 * rollbackしても残す）。そのため Spring proxy 経由の {@link AuditLogServiceImpl#recordDomainEventRequiredInNewTransaction}
 * （REQUIRES_NEW）で記録する。独立transactionを使えない監査先（mock・手動構築等）は、外側transactionが無い
 * （auto-commitで即時に確定する）場合に限り既存の {@link AuditLogService#recordDomainEventRequired} を使い、
 * 外側transactionがある場合は記録せず失敗させる（呼出元は送信しない）。</p>
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
    private static final int URI_MAX = 500;
    /** uri内の各識別子の上限。全項目が上限でもuri列長（500）に収まる長さにする。 */
    private static final int URI_PART_MAX = 64;

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
        persistIndependently(toEntry(APPLICATION_ATTEMPT, audit, 202, true, attemptState(audit)));
    }

    /** 送信後（成功・失敗）の必須監査。 */
    public void recordResult(SendAudit audit) {
        persistIndependently(toEntry(APPLICATION_RESULT, audit, audit.status(), audit.succeeded(), resultState(audit)));
    }

    /** 承認済み経路での送信前拒否。best effortで記録し、失敗しても拒否結果は変えない。 */
    public void recordDenied(SendAudit audit) {
        try {
            persistIndependently(toEntry(APPLICATION_DENIED, audit, audit.status(), false, resultState(audit)));
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
        entry.setUri(scopeUri(audit));
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

    /**
     * 承認束縛（決定ID・承認digest・用途・model）。digestは決定書に記載された承認内容のSHA-256全桁で、
     * 監査行から「どの承認内容で送ったか」を決定書と突合できるようにする（用途がMANAGEMENT_COPILOTなら
     * 決定ID・modelが上限長でも243文字で、列長255に収まる）。
     */
    static String bindingState(SendAudit audit) {
        return clip("decision=" + part(audit.decisionId())
                + ";digest=" + part(audit.approvalDigest())
                + ";purpose=" + part(audit.purpose())
                + ";model=" + part(audit.model()), STATE_MAX);
    }

    /**
     * 送信先と送信範囲（provider・tenant・法人・queryId・artifact・config hash）。
     * 各識別子は64文字以内に切り詰め、全体がuri列長（500）に収まる。
     */
    static String scopeUri(SendAudit audit) {
        return clip("ai-external-send://" + part(audit.destinationHost()) + "/" + part(audit.purpose())
                + "?provider=" + part(audit.provider())
                + "&tenant=" + part(audit.tenantId())
                + "&le=" + number(audit.legalEntityId())
                + "&query=" + part(audit.queryId())
                + "&artifact=" + number(audit.artifactVersionId())
                + "&cfg=" + part(audit.configHash()), URI_MAX);
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

    /**
     * 外側transactionから独立して永続化する。永続化できない・独立性を保証できない場合は例外を投げる
     * （ATTEMPTなら呼出元は送信しない）。
     */
    private void persistIndependently(AuditLog entry) {
        if (auditLogService == null) {
            throw new IllegalStateException("AI外部送信監査の永続化先がありません");
        }
        if (auditLogService instanceof AuditLogServiceImpl service && AopUtils.isAopProxy(service)) {
            // Spring proxy経由のREQUIRES_NEW。外側transactionがrollbackしても記録は残る。
            service.recordDomainEventRequiredInNewTransaction(entry);
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 独立transactionを使えない監査先では、外側rollbackで監査だけが消えるため記録しない。
            throw new IllegalStateException("外側transaction内ではAI外部送信監査を独立して永続化できません");
        }
        // 外側transactionが無い呼出しでは、insertはauto-commitで即時に確定する。
        auditLogService.recordDomainEventRequired(entry);
    }

    /** uri・before_stateの識別子。固定文字集合へ正規化し64文字以内に切り詰める。 */
    private static String part(String value) {
        return clip(code(value), URI_PART_MAX);
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
     * approvalDigestは承認内容digest、tenantId〜configHashは送信範囲の識別子（PIIではない）。
     */
    public record SendAudit(String attemptId, String traceId, String decisionId, String approvalDigest,
                            String provider, String model, String purpose, String destinationHost,
                            String tenantId, Long legalEntityId, String queryId, Long artifactVersionId,
                            String configHash, int promptBytes, Integer upperBoundYen,
                            Long dayTotalYen, Long monthTotalYen, String outcome, String reasonCode,
                            CopilotProviderUsage usage, Integer costYen, String costState, int status,
                            boolean succeeded, Long latencyMs) {
    }
}
