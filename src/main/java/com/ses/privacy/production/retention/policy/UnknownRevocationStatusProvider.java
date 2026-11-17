package com.ses.privacy.production.retention.policy;

import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.RevocationStatusProvider;

/**
 * 第1期の失効状態 provider。常に {@link RevocationStatus#UNKNOWN} を返す（W-3。CRL / OCSP 等の実 provider は D-03 の方式の決定後に
 * {@code PrivacyTrustSource} の adapter として追加する）。
 *
 * <p>trust anchor が登録されても、失効状態を確認できない限り policy は {@code POLICY_UNKNOWN} に閉じる
 * （trust registry が空であることと独立した2つ目の遮断）。
 */
public final class UnknownRevocationStatusProvider implements RevocationStatusProvider {

    @Override
    public RevocationStatus statusOf(String revocationReference) {
        return RevocationStatus.UNKNOWN;
    }
}
