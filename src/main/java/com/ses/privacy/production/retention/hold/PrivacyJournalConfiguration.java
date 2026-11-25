package com.ses.privacy.production.retention.hold;

import com.ses.featureenablement.config.FeatureEnablementProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * 独立 Journal 接続の Spring 登録（NF07-A1J、production-bridge-phased-plan.md §3.3）。
 *
 * <p>登録するのは専用 holder（{@link PrivacyJournalConnections}）と起動時検査（{@link PrivacyJournalStartupVerifier}）
 * だけで、{@code DataSource} 型の Bean は作らない（application の DataSource 自動構成と、単一候補を前提にする
 * {@code JdbcTemplate}・MyBatis-Plus の自動構成に影響させないため）。holder は journal 設定がある場合だけ内部 pool を作り、
 * 機能の開閉とは独立にする。登録は profile に依存しない（本番 adapter の生成だけが prod に限られる。起動時検査は起動を止めない）。
 * 起動時検査は application DB を読むだけで、application の {@code JdbcTemplate} が一意に決まらない場合は照会不能として扱う。
 */
@Configuration
@EnableConfigurationProperties(PrivacyJournalProperties.class)
public class PrivacyJournalConfiguration {

    @Bean(destroyMethod = "close")
    public PrivacyJournalConnections privacyJournalConnections(PrivacyJournalProperties properties) {
        return PrivacyJournalConnections.create(properties);
    }

    /**
     * 起動時検査（機能の開閉を読まない。起動を止めない。feature-enablement-console design §8.5 の 3）。再検査の間隔の下限は
     * {@code app.feature-enablement.journal-reverify-interval}（既定10分）。
     */
    @Bean
    public PrivacyJournalStartupVerifier privacyJournalStartupVerifier(
            PrivacyJournalConnections privacyJournalConnections,
            ObjectProvider<JdbcTemplate> applicationJdbcTemplate,
            ObjectProvider<FeatureEnablementProperties> featureEnablementProperties,
            ObjectProvider<Clock> clock,
            Environment environment) {
        boolean productionProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        FeatureEnablementProperties properties = featureEnablementProperties.getIfAvailable();
        Duration interval = properties == null ? null : properties.getJournalReverifyInterval();
        return new PrivacyJournalStartupVerifier(privacyJournalConnections, applicationJdbcTemplate::getIfUnique,
                productionProfile, interval, clock.getIfAvailable(Clock::systemUTC));
    }
}
