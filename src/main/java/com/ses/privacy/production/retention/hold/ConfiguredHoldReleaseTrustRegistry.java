package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.HoldReleaseTrustAnchor;
import com.ses.privacy.foundation.HoldReleaseTrustRegistry;

import java.util.Map;

/**
 * hold 解除 authority の trust anchor registry（NF07-REL）。第1期は空で構成する（D-03 の署名前は anchor を登録しない）。
 *
 * <p>空の間は解除の入口が開かず（404）、verifier も {@code TRUST_ANCHOR_UNKNOWN} で拒否する。設定・KMS / HSM からの読込は
 * NF07-TRUST で追加する。保持する map は不変で、構築後に変更できない。
 */
public final class ConfiguredHoldReleaseTrustRegistry implements HoldReleaseTrustRegistry {

    private final Map<String, HoldReleaseTrustAnchor> anchors;

    public ConfiguredHoldReleaseTrustRegistry(Map<String, HoldReleaseTrustAnchor> anchors) {
        this.anchors = anchors == null ? Map.of() : Map.copyOf(anchors);
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
