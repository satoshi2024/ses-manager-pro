package com.ses.privacy.production;

import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyEnforcementDecision;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyEnforcementReason;
import com.ses.privacy.foundation.PolicyEnforcementRequest;
import com.ses.privacy.production.retention.policy.UnavailablePolicyEnforcementPort;
import com.ses.privacy.production.retention.trust.PrivacyTrustSource;
import com.ses.privacy.production.retention.trust.UnconfiguredPrivacyTrustSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * NF07-NF08-LINK の内部判定 port の Spring 登録。prod 以外は常に拒否する port、prod は本番の判定（flag OFF では DB を読まない）。
 */
class PrivacyPolicyEnforcementConfigurationTest {

    private static final PolicyEnforcementRequest REQUEST = new PolicyEnforcementRequest("tenant-a",
            "NF08_AI_SUMMARY", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter", "NF07-POL-2026-0007",
            Instant.parse("2026-11-17T15:00:00Z"));

    @Test
    void prod以外のprofileでは常に拒否するportだけを登録する() {
        for (String profile : java.util.List.of("test", "dev")) {
            new ApplicationContextRunner()
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                    .withUserConfiguration(PrivacyPolicyEnforcementConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        PolicyEnforcementPort port = context.getBean(PolicyEnforcementPort.class);
                        assertThat(port).isInstanceOf(UnavailablePolicyEnforcementPort.class);
                        PolicyEnforcementDecision decision = port.check(REQUEST);
                        assertThat(decision.permitted()).isFalse();
                        assertThat(decision.reason()).isEqualTo(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE);
                    });
        }
    }

    @Test
    void prodでは本番の判定を登録し公開スイッチOFFならDBを読まずに拒否する() {
        PiiDataElementMapper dataElements = mock(PiiDataElementMapper.class);
        RetentionPolicyMapper policies = mock(RetentionPolicyMapper.class);
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(PrivacyFeatureProperties.class, PrivacyFeatureProperties::new)
                .withBean(PiiDataElementMapper.class, () -> dataElements)
                .withBean(RetentionPolicyMapper.class, () -> policies)
                .withBean(PrivacyTrustSource.class, UnconfiguredPrivacyTrustSource::new)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), ZoneOffset.UTC))
                .withUserConfiguration(PrivacyPolicyEnforcementConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PolicyEnforcementPort port = context.getBean(PolicyEnforcementPort.class);
                    assertThat(port).isNotInstanceOf(UnavailablePolicyEnforcementPort.class);
                    PolicyEnforcementDecision decision = port.check(REQUEST);
                    assertThat(decision.permitted()).isFalse();
                    assertThat(decision.reason()).isEqualTo(PolicyEnforcementReason.RETENTION_DISABLED);
                    verifyNoInteractions(dataElements, policies);
                });
    }
}
