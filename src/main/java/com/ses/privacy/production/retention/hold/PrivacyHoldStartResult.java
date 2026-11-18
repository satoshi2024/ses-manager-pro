package com.ses.privacy.production.retention.hold;

/**
 * hold 開始の結果（Journal の ACK を取り、projection を書いた後だけ返す）。
 *
 * @param holdId          hold ID
 * @param targetType      対象種別
 * @param status          {@code ACTIVE}
 * @param journalRevision Journal 上の revision
 */
public record PrivacyHoldStartResult(String holdId, String targetType, String status, long journalRevision) {
}
