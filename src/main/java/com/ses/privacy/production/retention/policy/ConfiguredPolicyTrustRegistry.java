package com.ses.privacy.production.retention.policy;

import com.ses.privacy.foundation.Ed25519PublicKeys;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.TrustedAuthority;

import java.util.Map;

/**
 * production の policy envelope の trust anchor registry（W-3）。第1期は空で構成する（D-03 の署名前は anchor を登録しない）。
 *
 * <p>空の間は全ての policy が {@code UNKNOWN_TRUST_ANCHOR}（{@code POLICY_DRIFT}）に閉じ、登録（NF07-REG）の入口も開かない。
 * D-03 の方式（設定・KMS / HSM）の adapter は {@code PrivacyTrustSource#policyAnchors} から本 class（または同じ検査をする実装）を
 * 返す（{@code policyAnchors()} は検証のたびに呼ばれるため、呼出しごとに現在の anchor から作って返してよい）。
 * 構築時に、map の key が {@code authorityId + ":" + keyId} と一致し、公開鍵が Ed25519 であることを検査し、合わなければ
 * 登録を拒否する（NF07-REL review P3-8 と同じ規則。照会の時に初めて {@code SIGNATURE_INVALID} になることを避ける）。保持する map は
 * 不変で、構築後に変更できない。
 */
public final class ConfiguredPolicyTrustRegistry implements ProductionPolicyTrustRegistry {

    private final Map<String, TrustedAuthority> anchors;

    public ConfiguredPolicyTrustRegistry(Map<String, TrustedAuthority> anchors) {
        Map<String, TrustedAuthority> copy = anchors == null ? Map.of() : Map.copyOf(anchors);
        copy.forEach((key, anchor) -> {
            if (!key.equals(anchor.authorityId() + ":" + anchor.keyId())) {
                throw new IllegalArgumentException("policy の trust anchor の key は authorityId:keyId でなければなりません");
            }
            if (!Ed25519PublicKeys.isEd25519(anchor.publicKey())) {
                throw new IllegalArgumentException("policy の trust anchor の鍵は Ed25519 の公開鍵でなければなりません");
            }
        });
        this.anchors = copy;
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
