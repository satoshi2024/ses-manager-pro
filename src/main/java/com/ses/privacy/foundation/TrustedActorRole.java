package com.ses.privacy.foundation;

/**
 * ポリシー照会を許可または制限されるアクターロール。
 */
public enum TrustedActorRole {
    ROLE_SYSTEM_SERVICE,     // システム内部連携ロール（照会可）
    ROLE_PRIVACY_AUDITOR,    // プライバシー・監査ロール（照会可）
    ROLE_ADMIN,              // システム管理者ロール（照会可）
    ROLE_UNPRIVILEGED_USER   // 一般業務ユーザー（照会権限なし・ACCESS_DENIED 対象）
}
