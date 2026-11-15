package com.ses.config;

import com.ses.service.ai.AiExternalSendApprovalMonitor;
import com.ses.service.ai.AiProductionApprovalGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** 空の環境変数相当の値でも監視設定が起動を妨げないことを確認する。 */
class AiConfigurationBindingTest {
    @Test
    void 空の警告日数は既定の30日に戻る() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(AiConfig.class)
                .withPropertyValues("ai.external-send-monitor.expiry-warning-days=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiConfig config = context.getBean(AiConfig.class);
                    Clock clock = Clock.systemUTC();
                    AiExternalSendApprovalMonitor monitor = new AiExternalSendApprovalMonitor(config,
                            new AiProductionApprovalGate(config, clock), clock, (MeterRegistry) null);
                    assertThat(monitor.warningDays()).isEqualTo(30);
                });
    }
}
