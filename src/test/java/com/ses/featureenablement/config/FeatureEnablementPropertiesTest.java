package com.ses.featureenablement.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * review rb-fe-a1 P3-5: 定期の評価の初回の遅延と署名の消去の cron も {@code app.feature-enablement.*} の範囲の検査に入れる
 * （{@code @Scheduled} の placeholder だけにあると、範囲外や不正な式が起動まで気付かれない）。
 */
class FeatureEnablementPropertiesTest {

    @Test
    void 既定値は検査を通り初回の遅延と消去のcronを持つ() {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();

        properties.validate();

        assertThat(properties.getEvaluationInitialDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getSignOffPurgeCron()).isEqualTo("0 40 3 * * *");
    }

    @Test
    void 初回の遅延の範囲外と不正なcronは起動を止める() {
        FeatureEnablementProperties delay = new FeatureEnablementProperties();
        delay.setEvaluationInitialDelay(Duration.ofMinutes(61));
        assertThatThrownBy(delay::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evaluation-initial-delay");

        FeatureEnablementProperties negative = new FeatureEnablementProperties();
        negative.setEvaluationInitialDelay(Duration.ofSeconds(-1));
        assertThatThrownBy(negative::validate).isInstanceOf(IllegalStateException.class);

        FeatureEnablementProperties cron = new FeatureEnablementProperties();
        cron.setSignOffPurgeCron("毎日");
        assertThatThrownBy(cron::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sign-off-purge-cron");
    }
}
