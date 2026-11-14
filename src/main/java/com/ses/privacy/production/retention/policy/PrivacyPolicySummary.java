package com.ses.privacy.production.retention.policy;

import java.time.LocalDateTime;

/**
 * 説明APIの一覧行（{@code m_retention_policy} の登録情報）。envelope の bytes・署名・法的な値は含めない。
 *
 * <p>{@code registrationStatus} は登録状態であって承認の証明ではない。policy が使えるかどうかは
 * 説明照会（{@link PrivacyPolicyExplanation#decisionStatus()}）だけで判断する。
 */
public record PrivacyPolicySummary(Long id, String policyId, String policyRevision, String moduleCode,
                                   String dataElementId, String targetAction, String targetProvider,
                                   String registrationStatus, String envelopeDigest,
                                   LocalDateTime registeredAt, LocalDateTime activatedAt) {
}
