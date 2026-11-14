package com.ses.privacy.production.retention.policy;

import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.TrustedAuthority;

import java.util.Map;

/**
 * production の trust anchor registry（W-3）。第1期は空で構成する（D-03 の署名前は anchor を登録しない）。
 *
 * <p>空の間は全ての policy が {@code UNKNOWN_TRUST_ANCHOR}（{@code POLICY_DRIFT}）に閉じる。設定からの読込と
 * KMS / HSM 連携は NF07-TRUST で追加する。保持する map は不変で、構築後に変更できない。
 */
public final class ConfiguredPolicyTrustRegistry implements ProductionPolicyTrustRegistry {

    private final Map<String, TrustedAuthority> anchors;

    public ConfiguredPolicyTrustRegistry(Map<String, TrustedAuthority> anchors) {
        this.anchors = anchors == null ? Map.of() : Map.copyOf(anchors);
    }

    /** 第1期の既定（空）。 */
    public static ConfiguredPolicyTrustRegistry empty() {
        return new ConfiguredPolicyTrustRegistry(Map.of());
    }

    @Override
    public Map<String, TrustedAuthority> anchors() {
        return anchors;
    }
}
