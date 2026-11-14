package com.ses.privacy.foundation;

/**
 * ポリシー評価判定ステータス。
 */
public enum PolicyDecisionStatus {
    APPROVED_ACTIVE,   // 検証合格（本批合成評価の結果であり、実行権限は付与しない）
    POLICY_UNKNOWN,    // 未登録、未承認、失効状態不明、ストア障害
    POLICY_EXPIRED,    // 期限切れ、未発効、または未発行
    POLICY_REVOKED,    // キーまたはエンベロープが明示的に失効
    POLICY_DRIFT,      // 署名改竄、スキーマ不正、Trust Anchor 未知、SoD 違反、照合不一致、バインディング不一致
    ACCESS_DENIED      // 呼び出し元の認証・権限・テナント不一致
}
