package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureRuntimeCondition;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * prod で {@code Feature.NF07_DSAR} にも決定ID束縛の有効期間を実行時条件として掛ける（A2a、production-bridge-phased-plan.md §5.1）。
 *
 * <p>{@link PrivacyDecisionWindowMonitor} は ON の capability（retention・dsar）を全て含めて束縛を評価するが、
 * {@link FeatureRuntimeCondition} の対象は1機能だけなので、{@code NF07_RETENTION} にしか掛からない。DSAR を別の最上位機能として
 * 登録した A2a から、本 class が同じ判定を {@code NF07_DSAR} へ掛ける（署名日前・失効の間は DSAR も OFF と同じ404）。
 * 判定と WARN・metric は monitor に任せ、本 class は状態を持たない。
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
        return monitor.satisfied();
    }
}
