package com.ses.privacy.foundation;

import java.util.Map;

/**
 * hold 解除 authority の trust anchor registry（NF07-REL）。key は {@code authorityId + ":" + keyId}。
 *
 * <p>D-03（署名鍵）の署名前は空で構成する。空なら全ての解除が {@link HoldReleaseRejection#TRUST_ANCHOR_UNKNOWN} に閉じ、
 * 解除の入口も開かない。D-03 の方式（AWS KMS 非対称署名）の anchor は {@code PrivacyTrustSource} の adapter
 * （{@code KmsPrivacyTrustSource}）が trust 文書から作る（REG・REL・説明照会が同じ Bean を使う）。
 */
public interface HoldReleaseTrustRegistry {
    Map<String, HoldReleaseTrustAnchor> anchors();
}
