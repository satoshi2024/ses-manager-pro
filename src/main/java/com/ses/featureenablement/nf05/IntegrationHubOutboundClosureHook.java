package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.service.FeatureEnablementClosureHook;
import com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * NF05 outbound を閉じる transaction（手動・自動・総スイッチ・起動の後の反映の閉鎖）で、barrier の行を {@code v1|OFF|0|<指紋>} に書き
 * 直し {@code NF05_OUTBOUND_OFF} を監査する（feature-enablement-console design §9.3。FE-D04）。開放状態の行を書いた後・commit の前に
 * 同じ transaction で呼ばれ（{@link FeatureEnablementClosureHook}）、lock の順は design §5.1 の (6)（barrier の行が最後）。次に開く起動が
 * OFF の行を見て隔離する（従来の kill switch は行を ON のまま残した）。
 */
@Component
public class IntegrationHubOutboundClosureHook implements FeatureEnablementClosureHook {

    private final ObjectProvider<IntegrationHubOutboundStartupBarrier> barrier;

    public IntegrationHubOutboundClosureHook(ObjectProvider<IntegrationHubOutboundStartupBarrier> barrier) {
        this.barrier = barrier;
    }

    @Override
    public void onClosed(String tenantId, Feature feature, String newState, String reasonCode) {
        if (feature != Feature.NF05_OUTBOUND) {
            return;
        }
        IntegrationHubOutboundStartupBarrier target = barrier.getIfAvailable();
        if (target == null) {
            throw new IllegalStateException("NF05 outbound の barrier が無いため閉鎖を記録できません");
        }
        target.recordRuntimeOff();
    }
}
