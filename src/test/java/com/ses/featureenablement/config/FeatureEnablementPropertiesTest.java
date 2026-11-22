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

    /** FE-A08（design §3.3・§7.2・§10）: 運用値の範囲の境と、refresh-interval は max-staleness の半分より短いという相互の関係。 */
    @Test
    void 運用値は範囲の境を受け範囲の外と相互の関係の違反は起動を止める() {
        FeatureEnablementProperties bounds = new FeatureEnablementProperties();
        bounds.setRefreshInterval(Duration.ofSeconds(30));
        bounds.setMaxStaleness(Duration.ofSeconds(300));
        bounds.setEvaluationInterval(Duration.ofMinutes(60));
        bounds.setJournalReverifyInterval(Duration.ofMinutes(1));
        bounds.setEvaluationInitialDelay(Duration.ZERO);
        bounds.validate();

        assertRejected(p -> p.setRefreshInterval(Duration.ofMillis(999)), "refresh-interval");
        assertRejected(p -> p.setRefreshInterval(Duration.ofSeconds(31)), "refresh-interval");
        assertRejected(p -> p.setMaxStaleness(Duration.ofSeconds(9)), "max-staleness");
        assertRejected(p -> p.setMaxStaleness(Duration.ofSeconds(301)), "max-staleness");
        assertRejected(p -> p.setEvaluationInterval(Duration.ofSeconds(59)), "evaluation-interval");
        assertRejected(p -> p.setEvaluationInterval(Duration.ofMinutes(61)), "evaluation-interval");
        assertRejected(p -> p.setJournalReverifyInterval(Duration.ofMinutes(61)), "journal-reverify-interval");
        assertRejected(p -> p.setSource(null), "source");
        assertRejected(p -> {
            p.setRefreshInterval(Duration.ofSeconds(10));
            p.setMaxStaleness(Duration.ofSeconds(20));
        }, "max-staleness の半分");
    }

    private static void assertRejected(java.util.function.Consumer<FeatureEnablementProperties> change, String message) {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        change.accept(properties);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
    }
}
