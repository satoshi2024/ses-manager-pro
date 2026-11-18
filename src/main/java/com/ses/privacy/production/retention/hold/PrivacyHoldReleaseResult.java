package com.ses.privacy.production.retention.hold;

/**
 * hold 解除の結果（NF07-REL）。
 *
 * @param holdId          解除した hold
 * @param status          {@code RELEASED}
 * @param releaseRevision Journal 上の HOLD_RELEASED の revision
 * @param replayed        同じ command の再送で、元の ACK を返したか
 */
public record PrivacyHoldReleaseResult(String holdId, String status, long releaseRevision, boolean replayed) {
}
