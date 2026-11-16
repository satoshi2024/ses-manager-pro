package com.ses.privacy.foundation;

import java.util.Map;

/**
 * hold 解除 authority の trust anchor registry（NF07-REL）。key は {@code authorityId + ":" + keyId}。
 *
 * <p>D-03（署名鍵）の署名前は空で構成する。空なら全ての解除が {@link HoldReleaseRejection#TRUST_ANCHOR_UNKNOWN} に閉じ、
 * 解除の入口も開かない。KMS / HSM 連携の実 registry は NF07-TRUST で追加する。
 */
public interface HoldReleaseTrustRegistry {
    Map<String, HoldReleaseTrustAnchor> anchors();
}
