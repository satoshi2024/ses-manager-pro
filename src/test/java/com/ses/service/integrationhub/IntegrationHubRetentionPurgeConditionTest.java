package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Conditional;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * N5-P2-3 / Q-12: retention/nonce purge schedulerはinbound・outboundのどちらかが有効なら生成する。
 * outboundだけの開放でdelivery payloadとreplay監査が永久に残らないことを固定する。
 */
class IntegrationHubRetentionPurgeConditionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ApiRetentionPurgeService.class, () -> mock(ApiRetentionPurgeService.class))
            .withBean(ApiNonceReplayService.class, () -> mock(ApiNonceReplayService.class))
            .withBean(IntegrationHubExternalApiProperties.class, () -> mock(IntegrationHubExternalApiProperties.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(IntegrationHubRetentionPurgeScheduler.class);

    @Test
    void schedulerIsCreatedForOutboundOnly() {
        runner.withPropertyValues("integration.hub.public-api.enabled=false",
                        "integration.hub.external-transport.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(IntegrationHubRetentionPurgeScheduler.class));
    }

    @Test
    void schedulerIsCreatedForInboundOnlyAndForBothDirections() {
        runner.withPropertyValues("integration.hub.public-api.enabled=true",
                        "integration.hub.external-transport.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(IntegrationHubRetentionPurgeScheduler.class));
        runner.withPropertyValues("integration.hub.public-api.enabled=TRUE",
                        "integration.hub.external-transport.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(IntegrationHubRetentionPurgeScheduler.class));
    }

    @Test
    void schedulerIsNotCreatedWhenBothDirectionsAreOffMissingOrInvalid() {
        runner.withPropertyValues("integration.hub.public-api.enabled=false",
                        "integration.hub.external-transport.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IntegrationHubRetentionPurgeScheduler.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(IntegrationHubRetentionPurgeScheduler.class));
        runner.withPropertyValues("integration.hub.public-api.enabled=yes",
                        "integration.hub.external-transport.enabled=1")
                .run(context -> assertThat(context).doesNotHaveBean(IntegrationHubRetentionPurgeScheduler.class));
    }

    @Test
    void schedulerNoLongerDependsOnlyOnThePublicApiProperty() {
        assertThat(IntegrationHubRetentionPurgeScheduler.class.getAnnotation(ConditionalOnProperty.class)).isNull();
        Conditional conditional = IntegrationHubRetentionPurgeScheduler.class.getAnnotation(Conditional.class);
        assertThat(conditional).isNotNull();
        assertThat(conditional.value()).containsExactly(IntegrationHubRetentionPurgeCondition.class);
    }
}
