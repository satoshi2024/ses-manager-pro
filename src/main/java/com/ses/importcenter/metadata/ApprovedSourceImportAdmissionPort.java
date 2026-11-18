package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.LogRedaction;
import com.ses.common.util.SecurityUtils;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.AuditLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 本番の取込admission port（Q-5）。既定はdeny。
 *
 * <p>署名済みData Owner決定IDを含む承認設定（{@link ImportAdmissionApprovalProperties}）が{@link ImportAdmissionApproval}で
 * completeと評価され、かつ現在のtenantが承認tenantと一致し、source codeが承認一覧に含まれる場合だけ受け付ける。
 * それ以外（未設定・部分設定・形式不正・digest不一致・署名日前・失効・未承認source・対象外entity）は全て
 * 403（{@code error.import.sourceNotApproved}）で、raw bodyを読む前に拒否する（fail-closed）。
 *
 * <p>判定結果は監査へ残す。許可は{@code recordRequired}で、記録できなければ許可しない（503）。拒否はbest-effortで記録し、
 * 記録の成否にかかわらず拒否する。監査には決定ID・source code・phase・固定の理由コードだけを書き、CSV本文や
 * hash全体は書かない（content phaseはsource SHA-256の先頭12桁だけ）。
 *
 * <p>承認設定は起動時に一度だけbindされる。content phaseでも評価をやり直し、日付の変わり目での失効と、
 * token発行時と異なる決定（決定ID・digest）を拒否する。
 */
@Component
public final class ApprovedSourceImportAdmissionPort implements ImportAdmissionPort {
    static final String AUDIT_METHOD = "ADMISSION";
    static final String AUDIT_URI = "/api/import-center/uploads";
    static final String ALLOWED_CODE = "IMPORT_ADMISSION_ALLOWED";
    static final String DENIED_CODE = "IMPORT_ADMISSION_DENIED";
    static final String TOKEN_PREFIX = "nf06-approval:";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApprovedSourceImportAdmissionPort.class);

    private final ImportAdmissionApprovalProperties properties;
    private final ObjectProvider<AuditLogService> auditProvider;
    private final AuditLogService fixedAudit;
    private final Clock clock;

    @Autowired
    public ApprovedSourceImportAdmissionPort(ImportAdmissionApprovalProperties properties,
                                             ObjectProvider<AuditLogService> auditProvider) {
        this(properties, auditProvider, null, Clock.systemUTC());
    }

    /** test用。監査と時刻を差し替える（auditがnullなら許可は記録できないため常に503になる）。 */
    ApprovedSourceImportAdmissionPort(ImportAdmissionApprovalProperties properties, AuditLogService audit,
                                      Clock clock) {
        this(properties, null, audit, clock);
    }

    private ApprovedSourceImportAdmissionPort(ImportAdmissionApprovalProperties properties,
                                              ObjectProvider<AuditLogService> auditProvider,
                                              AuditLogService fixedAudit, Clock clock) {
        this.properties = properties == null ? new ImportAdmissionApprovalProperties() : properties;
        this.auditProvider = auditProvider;
        this.fixedAudit = fixedAudit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context, ImportUploadSource source,
                                                ImportEntity entity, String schemaVersion, ImportEncoding encoding) {
        String sourceCode = source == null ? null : ImportAdmissionApproval.normalizeSourceCode(source.sourceCode());
        if (context == null || source == null || sourceCode == null || entity == null || schemaVersion == null
                || encoding == null) {
            throw deny("BEFORE_BODY", null, sourceCode, "request.invalid");
        }
        ImportAdmissionApproval.Evaluation evaluation = evaluate();
        if (!evaluation.complete()) {
            throw deny("BEFORE_BODY", null, sourceCode, "approval." + firstViolation(evaluation));
        }
        ImportAdmissionApproval.Snapshot approval = evaluation.snapshot();
        if (!approval.tenantId().equals(context.tenantId())) {
            throw deny("BEFORE_BODY", approval.decisionId(), sourceCode, "tenant.notApproved");
        }
        if (entity != ImportAdmissionApproval.APPROVED_ENTITY || !SesImportV1Schema.VERSION.equals(schemaVersion)) {
            throw deny("BEFORE_BODY", approval.decisionId(), sourceCode, "entity.notApproved");
        }
        if (!approval.approvesSourceCode(sourceCode)) {
            throw deny("BEFORE_BODY", approval.decisionId(), sourceCode, "source.notApproved");
        }
        auditAllowed("BEFORE_BODY", approval.decisionId(), sourceCode, null);
        return new ImportAdmissionToken(context.tenantId(), context.actorUserId(), context.legalEntityId(),
                source.id(), source.version(), entity, schemaVersion, encoding,
                bindingPrefix(approval) + UUID.randomUUID());
    }

    @Override
    public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String sourceSha256) {
        if (token == null || token.fixtureKey() == null || sourceSha256 == null
                || !SHA256.matcher(sourceSha256).matches()) {
            throw deny("CONTENT", null, null, "request.invalid");
        }
        ImportAdmissionApproval.Evaluation evaluation = evaluate();
        if (!evaluation.complete()) {
            throw deny("CONTENT", null, null, "approval." + firstViolation(evaluation));
        }
        ImportAdmissionApproval.Snapshot approval = evaluation.snapshot();
        if (!token.fixtureKey().startsWith(bindingPrefix(approval))
                || !approval.tenantId().equals(token.tenantId())
                || token.entity() != ImportAdmissionApproval.APPROVED_ENTITY
                || !SesImportV1Schema.VERSION.equals(token.schemaVersion())) {
            throw deny("CONTENT", approval.decisionId(), null, "token.notBound");
        }
        auditAllowed("CONTENT", approval.decisionId(), null, sourceSha256.substring(0, 12));
        return new ImportAdmissionManifest(token.fixtureKey(), token.tenantId(), token.legalEntityId(),
                token.sourceSystemId(), token.sourceVersion(), ImportEntity.CUSTOMER, SesImportV1Schema.VERSION,
                token.encoding(), sourceSha256, null, null, null, null, "A1A_CONTENT");
    }

    private ImportAdmissionApproval.Evaluation evaluate() {
        return ImportAdmissionApproval.evaluate(properties, ImportAdmissionApproval.businessDate(clock));
    }

    /** tokenを発行時の決定（決定ID・digestの先頭16桁）へ束縛する接頭辞。 */
    private static String bindingPrefix(ImportAdmissionApproval.Snapshot approval) {
        return TOKEN_PREFIX + approval.decisionId() + ":" + approval.approvalDigest().substring(0, 16) + ":";
    }

    private static String firstViolation(ImportAdmissionApproval.Evaluation evaluation) {
        return evaluation.violations().isEmpty() ? "unavailable" : evaluation.violations().get(0);
    }

    private void auditAllowed(String phase, String decisionId, String sourceCode, String shaPrefix) {
        AuditLogService audit = audit();
        String uri = auditUri(phase, decisionId, sourceCode, "ALLOWED", null, shaPrefix);
        if (audit == null) {
            log.error("[取込] admission許可を監査へ記録できないため受け付けません: phase={} reason=auditUnavailable", phase);
            throw BusinessException.of(503, "error.import.auditUnavailable");
        }
        try {
            audit.recordRequired(SecurityUtils.currentUsername(), AUDIT_METHOD, uri, 200, ALLOWED_CODE, true);
        } catch (RuntimeException failure) {
            log.error("[取込] admission許可の監査記録に失敗したため受け付けません: phase={} exceptionType={}",
                    phase, LogRedaction.exceptionType(failure));
            throw BusinessException.of(503, "error.import.auditUnavailable");
        }
    }

    private BusinessException deny(String phase, String decisionId, String sourceCode, String reason) {
        log.warn("[取込] admissionを拒否しました: phase={} reason={}", phase, reason);
        AuditLogService audit = audit();
        if (audit != null) {
            try {
                audit.record(SecurityUtils.currentUsername(), AUDIT_METHOD,
                        auditUri(phase, decisionId, sourceCode, "DENIED", reason, null), 403, DENIED_CODE, false);
            } catch (RuntimeException failure) {
                log.warn("[取込] admission拒否の監査記録に失敗しました（拒否は維持）: exceptionType={}",
                        LogRedaction.exceptionType(failure));
            }
        }
        return BusinessException.of(403, "error.import.sourceNotApproved");
    }

    private AuditLogService audit() {
        if (fixedAudit != null) return fixedAudit;
        return auditProvider == null ? null : auditProvider.getIfAvailable();
    }

    /** {@code t_audit_log.uri}（500文字）へ収まる固定形式。値は形式検査済みのID・codeだけを入れる。 */
    static String auditUri(String phase, String decisionId, String sourceCode, String result, String reason,
                           String shaPrefix) {
        StringBuilder uri = new StringBuilder(AUDIT_URI).append(";phase=").append(phase)
                .append(";result=").append(result)
                .append(";decision=").append(decisionId == null ? "-" : decisionId)
                .append(";source=").append(sourceCode == null ? "-" : sourceCode);
        if (reason != null) uri.append(";reason=").append(reason);
        if (shaPrefix != null) uri.append(";sha=").append(shaPrefix);
        return uri.length() <= 500 ? uri.toString() : uri.substring(0, 500);
    }
}
