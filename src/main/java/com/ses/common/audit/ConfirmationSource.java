package com.ses.common.audit;

/**
 * 確認が成立したチャネル区分。
 */
public enum ConfirmationSource {
    MANUAL_API,
    SCHEDULER_POLL,
    PROVIDER_SYNC,
    PROVIDER_CALLBACK,
    LEGACY_UNRESOLVED
}
