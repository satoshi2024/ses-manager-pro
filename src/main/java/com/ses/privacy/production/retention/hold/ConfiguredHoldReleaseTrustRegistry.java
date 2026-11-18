package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.HoldReleaseTrustAnchor;
import com.ses.privacy.foundation.HoldReleaseTrustRegistry;

import java.util.Map;

/**
 * hold 解除 authority の trust anchor registry（NF07-REL）。第1期は空で構成する（D-03 の署名前は anchor を登録しない）。
 *
 * <p>空の間は解除の入口が開かず（404）、verifier も {@code TRUST_ANCHOR_UNKNOWN} で拒否する。D-03 の方式（設定・KMS / HSM）の
 * adapter は {@code PrivacyTrustSource#holdReleaseAnchors} から本 class（または同じ検査をする実装）を返す
 * （{@code holdReleaseAnchors()} は検証・開放の判定のたびに呼ばれるため、呼出しごとに現在の anchor から作って返してよい）。
 * 構築時に map の key が
 * {@link HoldReleaseTrustAnchor#anchorId()} と一致することを検査する（主体の正規形と Ed25519 の鍵は anchor の constructor が
 * 検査する）。保持する map は不変で、構築後に変更できない。
 */
public final class ConfiguredHoldReleaseTrustRegistry implements HoldReleaseTrustRegistry {

    private final Map<String, HoldReleaseTrustAnchor> anchors;

    public ConfiguredHoldReleaseTrustRegistry(Map<String, HoldReleaseTrustAnchor> anchors) {
        Map<String, HoldReleaseTrustAnchor> copy = anchors == null ? Map.of() : Map.copyOf(anchors);
        copy.forEach((key, anchor) -> {
            if (!key.equals(anchor.anchorId())) {
                throw new IllegalArgumentException("解除 authority の trust anchor の key は authorityId:keyId でなければなりません");
            }
        });
        this.anchors = copy;
    }

    /** 第1期の既定（空）。 */
    public static ConfiguredHoldReleaseTrustRegistry empty() {
        return new ConfiguredHoldReleaseTrustRegistry(Map.of());
    }

    @Override
    public Map<String, HoldReleaseTrustAnchor> anchors() {
        return anchors;
    }
}
