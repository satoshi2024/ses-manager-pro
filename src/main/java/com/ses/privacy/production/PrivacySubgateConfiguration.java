package com.ses.privacy.production;

import com.ses.featureenablement.evaluation.SubgateMonitor;
import com.ses.privacy.production.retention.hold.PrivacyHoldDecisions;
import com.ses.privacy.production.retention.hold.PrivacyHoldJournal;
import com.ses.privacy.production.retention.hold.PrivacyHoldReleaseService;
import com.ses.privacy.production.retention.policy.PrivacyPolicyRegistrationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NF07 保持・保全の副入口の固有の条件（feature-enablement-console design §7.4・§8.5 の 4・9。FE-C04）。{@link SubgateMonitor} が定期の
 * 評価のたびに読み、成否の変化を履歴と通知に出す。各条件は入口の gate と同じ判定を呼ぶ（写しを作らない）。
 *
 * <ul>
 *   <li>{@code HOLD_START}: D-05 と hold の前提（{@link PrivacyHoldDecisions#evaluate()}）、Journal の開放の条件の結果が appender
 *       （{@link PrivacyHoldJournal#appendAvailability()}）。</li>
 *   <li>{@code REL}: {@link PrivacyHoldReleaseService#isOpen()}（D-03・D-05・trust の構造・anchor・verifier・失効状態）。</li>
 *   <li>{@code REG}: {@link PrivacyPolicyRegistrationService#isOpen()}（D-01・D-02・D-03・trust の構造・verifier・anchor）。</li>
 * </ul>
 * 部品が無い・例外なら不成立（閉）。
 */
@Configuration
public class PrivacySubgateConfiguration {

    @Bean
    public SubgateMonitor.Condition privacyHoldStartSubgateCondition(ObjectProvider<PrivacyHoldDecisions> decisions,
                                                                     ObjectProvider<PrivacyHoldJournal> journal) {
        return SubgateMonitor.Condition.of("HOLD_START", () -> {
            PrivacyHoldDecisions resolvedDecisions = decisions.getIfAvailable();
            PrivacyHoldJournal resolvedJournal = journal.getIfAvailable();
            return resolvedDecisions != null && resolvedJournal != null && resolvedDecisions.evaluate().ready()
                    && resolvedJournal.appendAvailability() == PrivacyHoldJournal.Availability.AVAILABLE;
        });
    }

    @Bean
    public SubgateMonitor.Condition privacyHoldReleaseSubgateCondition(
            ObjectProvider<PrivacyHoldReleaseService> releaseService) {
        return SubgateMonitor.Condition.of("REL", () -> {
            PrivacyHoldReleaseService service = releaseService.getIfAvailable();
            return service != null && service.isOpen();
        });
    }

    @Bean
    public SubgateMonitor.Condition privacyPolicyRegistrationSubgateCondition(
            ObjectProvider<PrivacyPolicyRegistrationService> registrationService) {
        return SubgateMonitor.Condition.of("REG", () -> {
            PrivacyPolicyRegistrationService service = registrationService.getIfAvailable();
            return service != null && service.isOpen();
        });
    }
}
