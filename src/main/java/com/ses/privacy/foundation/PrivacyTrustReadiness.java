package com.ses.privacy.foundation;

import java.util.Collection;

/**
 * trust anchor と失効 provider の組が、要求を受け付けられる状態かの判定（開放の gate。NF07-REG・NF07-REL 共用）。
 *
 * <p>登録された anchor のうち、失効 provider が {@link RevocationStatus#ACTIVE} と答えるものを数える。UNKNOWN・REVOKED・
 * null・例外・provider 無しは数えない（第1期の常に UNKNOWN の provider では常に0件で、入口は開かない）。必要数に達した時点で
 * 照会をやめる（D-03 の方式の provider が外部照会でも、開放の判定の照会を必要数までに抑える）。状態を持たない。
 */
final class PrivacyTrustReadiness {

    private PrivacyTrustReadiness() {
    }

    /**
     * 失効状態が ACTIVE の anchor の数（{@code enough} に達したら打ち切る）。
     *
     * @param anchorIds   登録された anchor（{@code authorityId:keyId}）
     * @param revocations 失効 provider（null なら0件）
     * @param enough      必要数（1以上）
     */
    static int activeAnchors(Collection<String> anchorIds, RevocationStatusProvider revocations, int enough) {
        if (anchorIds == null || anchorIds.isEmpty() || revocations == null || enough < 1) {
            return 0;
        }
        int active = 0;
        for (String anchorId : anchorIds) {
            RevocationStatus status;
            try {
                status = anchorId == null ? null : revocations.statusOf(anchorId);
            } catch (RuntimeException e) {
                status = null;
            }
            if (status == RevocationStatus.ACTIVE && ++active >= enough) {
                return active;
            }
        }
        return active;
    }
}
