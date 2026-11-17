package com.ses.privacy.foundation;

/**
 * 内部判定 port（{@link PolicyEnforcementPort}）の結果コード（NF07-NF08-LINK、production-bridge-phased-plan.md §8）。
 *
 * <p>log・metric の tag に使う有限の固定コードで、tenant・policy ID・PII・設定値を含まない。説明照会の
 * {@link PolicyDecisionStatus} と違い、呼出し側（NF08 の送信 gate 等）が送信を拒否する理由を区別するためだけに使う。
 * 細かな原因は {@link PolicyEnforcementDecision#detail()}（{@link PolicyFailureReason}）に残す。
 */
public enum PolicyEnforcementReason {
    /** 判定の全ての前提を満たした（拒否ではない）。 */
    PERMITTED,
    /** 公開スイッチ {@code privacy.retention.enabled} が OFF、または決定ID束縛の有効期間外（policy source を読まない）。 */
    RETENTION_DISABLED,
    /** 判定部品が構成されていない（prod 以外の profile、Bean を取得できない等）。 */
    ENFORCEMENT_UNAVAILABLE,
    /** 判定要求の key・期待値が不完全。 */
    REQUEST_INVALID,
    /** policy が未登録・未承認・失効状態不明・store 障害（説明照会の {@code POLICY_UNKNOWN} に当たる）。 */
    POLICY_UNKNOWN,
    /** policy・署名 authority の期限切れ、未発効、未発行（{@code POLICY_EXPIRED}）。 */
    POLICY_EXPIRED,
    /** 署名鍵または envelope の失効（{@code POLICY_REVOKED}）。 */
    POLICY_REVOKED,
    /** 署名・digest・binding・SoD・trust anchor の不整合（{@code POLICY_DRIFT}）。 */
    POLICY_DRIFT,
    /** current policy の ID が、呼出し側の承認に記載された policy ID と一致しない。 */
    POLICY_ID_MISMATCH
}
