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
    @org.junit.jupiter.api.Test
    void 全NF05生成条件がBooleanの別表記で一致する() {
        for (String value : java.util.List.of("true", "yes", "on", "1")) {
            var environment = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("integration.hub.external-transport.enabled", value)
                    .withProperty("integration.hub.public-api.enabled", value)
                    .withProperty("app.scheduling.enabled", value);
            var context = org.mockito.Mockito.mock(org.springframework.context.annotation.ConditionContext.class);
            org.mockito.Mockito.when(context.getEnvironment()).thenReturn(environment);
            org.junit.jupiter.api.Assertions.assertTrue(new IntegrationHubOutboundCondition().matches(context, null));
            org.junit.jupiter.api.Assertions.assertTrue(new IntegrationHubInboundCondition().matches(context, null));
            org.junit.jupiter.api.Assertions.assertTrue(new IntegrationHubDeliverySchedulingCondition().matches(context, null));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ApiRetentionPurgeService.class, () -> mock(ApiRetentionPurgeService.class))
            .withBean(ApiNonceReplayService.class, () -> mock(ApiNonceReplayService.class))
            .withBean(IntegrationHubExternalApiProperties.class, () -> mock(IntegrationHubExternalApiProperties.class))
            .withBean(Clock.class, Clock::systemUTC)
            // NF07-A1H-2: D7 の保全hold gate（生成条件には関係しない）
            .withBean(com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard.class,
                    com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards::noHoldsGuard)
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
    void schedulerIsNotCreatedWhenBothDirectionsAreOffOrMissing() {
        runner.withPropertyValues("integration.hub.public-api.enabled=false",
                        "integration.hub.external-transport.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IntegrationHubRetentionPurgeScheduler.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(IntegrationHubRetentionPurgeScheduler.class));
        runner.withPropertyValues("integration.hub.public-api.enabled=yes",
                        "integration.hub.external-transport.enabled=1")
                .run(context -> assertThat(context).hasSingleBean(IntegrationHubRetentionPurgeScheduler.class));
    }

    @Test
    void schedulerNoLongerDependsOnlyOnThePublicApiProperty() {
        assertThat(IntegrationHubRetentionPurgeScheduler.class.getAnnotation(ConditionalOnProperty.class)).isNull();
        Conditional conditional = IntegrationHubRetentionPurgeScheduler.class.getAnnotation(Conditional.class);
        assertThat(conditional).isNotNull();
        assertThat(conditional.value()).containsExactly(IntegrationHubRetentionPurgeCondition.class);
    }

    /**
     * FE-D02（design §9.1 の 5a・review P2-5・P3-N3）: 起動の前の読取りが書いた purge 用の property があればその値で決める（NF05 を閉じた
     * 後の起動でも purge を作る）。read snapshot の purge も同じ条件。
     */
    @Test
    void purge用のpropertyがあれば両方向が閉でも作り無ければ従来の判定() {
        runner.withPropertyValues("integration.hub.public-api.enabled=false",
                        "integration.hub.external-transport.enabled=false", "integration.hub.retention-purge.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(IntegrationHubRetentionPurgeScheduler.class));
        runner.withPropertyValues("integration.hub.public-api.enabled=true",
                        "integration.hub.retention-purge.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IntegrationHubRetentionPurgeScheduler.class));
        Conditional snapshot = ExternalApiReadSnapshotPurgeScheduler.class.getAnnotation(Conditional.class);
        assertThat(snapshot.value()).containsExactly(IntegrationHubRetentionPurgeCondition.class);
        Conditional metrics = IntegrationHubDeliveryMetrics.class.getAnnotation(Conditional.class);
        assertThat(metrics.value()).containsExactly(IntegrationHubRetentionPurgeCondition.class);
    }
}
