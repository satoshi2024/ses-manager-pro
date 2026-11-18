package com.ses.privacy.production.retention.policy;

/**
 * policy の登録・有効化の結果（NF07-REG）。envelope の bytes・署名・法的な値は含めない。
 *
 * <p>{@code registrationStatus} は登録状態（{@code CANDIDATE} / {@code CURRENT} / {@code SUPERSEDED} / {@code REVOKED}）で、
 * 承認の証明ではない。policy が使えるかどうかは説明照会（{@code GET /api/privacy/policies/evaluate}）だけで判断する。
 *
 * @param id                   行 ID
 * @param policyId             policy ID
 * @param policyRevision       revision
 * @param registrationStatus   登録状態
 * @param envelopeDigest       canonical bytes の SHA-256
 * @param supersededId         有効化で SUPERSEDED にした旧 current の行 ID（無ければ null）
 * @param replayed             同じ要求の再送で、状態を変えずに元の結果を返したか
 */
public record PrivacyPolicyRegistrationResult(Long id, String policyId, String policyRevision,
                                              String registrationStatus, String envelopeDigest, Long supersededId,
                                              boolean replayed) {
}
