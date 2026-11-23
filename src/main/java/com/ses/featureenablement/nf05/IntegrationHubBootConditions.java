package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureRuntimeCondition;
import com.ses.config.integrationhub.IntegrationHubApprovals;
import com.ses.config.integrationhub.IntegrationHubEnablementDecision;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Optional;

/**
 * NF05 の起動時の値を実効値の条件にする（feature-enablement-console design §9.2・§9.3。FE-D02）。DB の開放状態が {@code OPEN} でも、
 * この起動で閉じた方向（起動の前の読取りの {@code READ_FAILED}・{@code CLOSED:<code>}、安全網）は実効値が閉で、画面の閉じている理由に
 * 「この起動では閉」（{@code nf05.boot.*}）を出す。{@link com.ses.config.feature.FeatureSwitches#isEnabled} は全ての実行時条件の論理積
 * のため、property の源では設定値と同じ値になる（従来どおり）。
 */
@Configuration
public class IntegrationHubBootConditions {

    @Bean
    public FeatureRuntimeCondition integrationHubInboundBootCondition(IntegrationHubExternalApiProperties properties,
                                                                      Environment environment) {
        return new BootCondition(Feature.NF05_INBOUND, properties, environment);
    }

    @Bean
    public FeatureRuntimeCondition integrationHubOutboundBootCondition(IntegrationHubExternalApiProperties properties,
                                                                       Environment environment) {
        return new BootCondition(Feature.NF05_OUTBOUND, properties, environment);
    }

    /** 1つの方向の起動時の値の条件。 */
    static final class BootCondition implements FeatureRuntimeCondition {

        private final Feature feature;
        private final IntegrationHubExternalApiProperties properties;
        private final Environment environment;

        BootCondition(Feature feature, IntegrationHubExternalApiProperties properties, Environment environment) {
            this.feature = feature;
            this.properties = properties;
            this.environment = environment;
        }

        @Override
        public Feature feature() {
            return feature;
        }

        @Override
        public boolean satisfied() {
            return feature == Feature.NF05_INBOUND ? IntegrationHubRuntimeSwitch.inboundBootOpen(properties)
                    : IntegrationHubRuntimeSwitch.outboundBootOpen(properties);
        }

        /**
         * 閉じている理由: {@code nf05.boot.readFailed}（起動の前に DB を読めなかった）・{@code nf05.boot.closed}（起動の前の判定で閉。
         * 理由は履歴・log）・{@code nf05.boot.safetyNet}（起動の境界の検査の安全網で閉）・{@code nf05.boot.notRestarted}（property の源、
         * または起動の後に開いた。次の起動で開く）。
         */
        @Override
        public Optional<String> unsatisfiedReason() {
            if (satisfied()) {
                return Optional.empty();
            }
            String direction = feature == Feature.NF05_INBOUND ? "inbound" : "outbound";
            if (properties != null && properties.bootClosureCode(feature == Feature.NF05_INBOUND) != null) {
                return Optional.of("nf05.boot.safetyNet");
            }
            String result = environment == null ? null
                    : environment.getProperty(IntegrationHubApprovals.BOOT_PREFIX + direction + ".result");
            if (IntegrationHubEnablementDecision.RESULT_READ_FAILED.equals(result)) {
                return Optional.of("nf05.boot.readFailed");
            }
            if (result != null && result.startsWith(IntegrationHubEnablementDecision.RESULT_CLOSED_PREFIX)) {
                return Optional.of("nf05.boot.closed");
            }
            return Optional.of("nf05.boot.notRestarted");
        }
    }
}
