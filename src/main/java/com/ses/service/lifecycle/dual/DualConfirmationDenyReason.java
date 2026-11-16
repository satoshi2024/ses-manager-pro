package com.ses.service.lifecycle.dual;

import com.ses.service.lifecycle.evidence.LifecycleAuthorizationFence;

/**
 * 拒否eventの {@code reason_code}（NF01細粒度epoch計画 §5.8）。値は固定のcodeだけで、文書title・storage key・
 * 業務comment・client指定のhash・氏名を含めない。
 *
 * <p>状態の不成立（DUAL以外のtask、案件・タスクの状態、世代不一致、supersede済み・決定済み）は計画の一覧に専用codeが無いため
 * {@link #SUBMISSION_STALE} にまとめる（HTTPの状態とmessage keyは §5.9 のとおり分ける）。</p>
 */
public enum DualConfirmationDenyReason {
    NOT_FOUND_OR_FOREIGN,
    SCOPE_DENIED,
    CONFIRMER_ROLE_DENIED,
    SELF_CONFIRMATION,
    EVIDENCE_TYPE_UNSUPPORTED,
    EVIDENCE_TARGET_UNSUPPORTED,
    EVIDENCE_SELECTION_INVALID,
    EVIDENCE_VERSION_REQUIRED,
    EVIDENCE_VERSION_NOT_CLEAN,
    EVIDENCE_UNAVAILABLE,
    STORAGE_INTEGRITY,
    SUBMISSION_STALE,
    AUTHORIZATION_CHANGED_GLOBAL,
    AUTHORIZATION_CHANGED_ACTOR,
    AUTHORIZATION_CHANGED_ENGINEER,
    AUTHORIZATION_CHANGED_ORGANIZATION,
    AUTHORIZATION_CHANGED_DOCUMENT,
    IDEMPOTENCY_CONFLICT,
    RETRY_EXHAUSTED,
    EPOCH_UNAVAILABLE;

    /** fenceの変化成分に対応するreason。 */
    public static DualConfirmationDenyReason authorizationChanged(LifecycleAuthorizationFence.Component component) {
        if (component == null) {
            return AUTHORIZATION_CHANGED_GLOBAL;
        }
        return switch (component) {
            case GLOBAL -> AUTHORIZATION_CHANGED_GLOBAL;
            case ACTOR -> AUTHORIZATION_CHANGED_ACTOR;
            case ENGINEER -> AUTHORIZATION_CHANGED_ENGINEER;
            case ORGANIZATION -> AUTHORIZATION_CHANGED_ORGANIZATION;
        };
    }

    /** 業務例外のmessage keyから推定するreason（専用の拒否を経由しない協力部品の例外用）。 */
    public static DualConfirmationDenyReason fromMessageKey(String messageKey) {
        if (messageKey == null) {
            return SCOPE_DENIED;
        }
        return switch (messageKey) {
            case "error.lifecycle.taskNotFound", "error.lifecycle.caseNotFound", "error.document.notFound" ->
                    NOT_FOUND_OR_FOREIGN;
            case "error.authorization.epochUnavailable" -> EPOCH_UNAVAILABLE;
            case "error.lifecycle.evidenceVersionRequired" -> EVIDENCE_VERSION_REQUIRED;
            case "error.lifecycle.evidenceVersionNotClean" -> EVIDENCE_VERSION_NOT_CLEAN;
            case "error.lifecycle.evidenceDocumentTypeUnsupported" -> EVIDENCE_TYPE_UNSUPPORTED;
            case "error.lifecycle.evidenceDocumentTargetUnsupported" -> EVIDENCE_TARGET_UNSUPPORTED;
            case "error.lifecycle.evidenceDocumentUnavailable" -> EVIDENCE_UNAVAILABLE;
            case "error.lifecycle.submissionStale", "error.lifecycle.invalidStateTransition",
                 "error.lifecycle.caseNotActive" -> SUBMISSION_STALE;
            case "error.lifecycle.idempotencyConflict" -> IDEMPOTENCY_CONFLICT;
            default -> SCOPE_DENIED;
        };
    }
}
