package com.ses.common.audit;

/**
 * 確認・監査記録の実行主体区分。
 * 文字列を自由入力させず、DB値と同じ閉じた集合で扱う。
 */
public enum ActorType {
    HUMAN,
    SYSTEM,
    PROVIDER,
    LEGACY_UNRESOLVED
}
