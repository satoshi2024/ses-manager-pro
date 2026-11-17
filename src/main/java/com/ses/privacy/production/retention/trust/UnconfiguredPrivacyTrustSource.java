package com.ses.privacy.production.retention.trust;

import com.ses.privacy.foundation.HoldReleaseTrustRegistry;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.RevocationStatusProvider;
import com.ses.privacy.production.retention.hold.ConfiguredHoldReleaseTrustRegistry;
import com.ses.privacy.production.retention.policy.ConfiguredPolicyTrustRegistry;
import com.ses.privacy.production.retention.policy.UnknownRevocationStatusProvider;

/**
 * 第1期の trust（D-03 の方式の決定前）。policy・hold 解除の anchor はどちらも0件、失効状態は常に UNKNOWN。
 *
 * <p>この間は、policy の登録・有効化（NF07-REG）と hold 解除（NF07-REL）の入口は404のまま（開放の gate が trust を満たさない）、
 * 説明照会は {@code UNKNOWN_TRUST_ANCHOR} / {@code POLICY_UNKNOWN} に閉じる。決定ID を設定しても開かない。
 */
public final class UnconfiguredPrivacyTrustSource implements PrivacyTrustSource {

    private final ProductionPolicyTrustRegistry policyAnchors = ConfiguredPolicyTrustRegistry.empty();
    private final HoldReleaseTrustRegistry holdReleaseAnchors = ConfiguredHoldReleaseTrustRegistry.empty();
    private final RevocationStatusProvider revocations = new UnknownRevocationStatusProvider();

    @Override
    public ProductionPolicyTrustRegistry policyAnchors() {
        return policyAnchors;
    }

    @Override
    public HoldReleaseTrustRegistry holdReleaseAnchors() {
        return holdReleaseAnchors;
    }

    @Override
    public RevocationStatusProvider revocations() {
        return revocations;
    }
}
