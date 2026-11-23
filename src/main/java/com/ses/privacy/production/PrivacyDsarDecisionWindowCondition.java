package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureRuntimeCondition;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * prod で {@code Feature.NF07_DSAR} にも決定ID束縛の有効期間を実行時条件として掛ける（A2a、production-bridge-phased-plan.md §5.1）。
 *
 * <p>{@link PrivacyDecisionWindowMonitor} は状態を capability ごとに持つ（feature-enablement-console design §8.5 の 5）。
 * {@link FeatureRuntimeCondition} の対象は1機能だけなので、monitor は {@code NF07_RETENTION} に retention の状態を掛け、本 class が
 * DSAR の状態（D-01・D-06・D-09 の期間と、D-09 が dsar を承認していること）を {@code NF07_DSAR} へ掛ける（署名日前・失効の間は
 * DSAR も OFF と同じ404）。D-06 の失効は DSAR だけを閉じ、保持・保全は閉じない（D-4）。判定と WARN・metric は monitor に任せ、
 * 本 class は状態を持たない。
 */
@Component
@Profile("prod")
public class PrivacyDsarDecisionWindowCondition implements FeatureRuntimeCondition {

    private final PrivacyDecisionWindowMonitor monitor;

    public PrivacyDsarDecisionWindowCondition(PrivacyDecisionWindowMonitor monitor) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    @Override
    public Feature feature() {
        return Feature.NF07_DSAR;
    }

    @Override
    public boolean satisfied() {
        return monitor.satisfied(PrivacyAccessGuard.Capability.DSAR);
    }

    @Override
    public Optional<String> unsatisfiedReason() {
        return monitor.reason(PrivacyAccessGuard.Capability.DSAR);
    }
}
