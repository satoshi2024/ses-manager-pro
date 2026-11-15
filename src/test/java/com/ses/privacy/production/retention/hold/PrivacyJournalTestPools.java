package com.ses.privacy.production.retention.hold;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Duration;

/**
 * 実MySQLのtestが、本番 holder（{@link PrivacyJournalConnections}）と同じ pool 設定（session time zone の固定、
 * driver の UTC 変換、初期接続なし等）で Journal へ接続するためのhelper。
 *
 * <p>Testcontainers の MySQL は自己署名証明書のため {@code sslMode=VERIFY_IDENTITY} を満たせない。そのため設定契約の
 * 検査（{@link PrivacyJournalConnections#contractViolations}）は通さず、pool 設定だけを本番と同じにする。
 * 契約の検査は {@code PrivacyJournalStartupVerifierTest} が Docker 無しで固定する。別 package の test からも使う。
 */
public final class PrivacyJournalTestPools {

    private PrivacyJournalTestPools() {
    }

    public static HikariDataSource pool(String jdbcUrl, String username, String password) {
        return new HikariDataSource(config(jdbcUrl, username, password));
    }

    /**
     * 本番と同じ設定のうち session time zone だけを {@code zone} に変えた pool（時区検査の不適合を明示的に作る。
     * container の既定 time_zone に依存しない。Review P3-6）。
     */
    public static HikariDataSource poolWithSessionTimeZone(String jdbcUrl, String username, String password,
                                                           String zone) {
        HikariConfig config = config(jdbcUrl, username, password);
        config.setConnectionInitSql("SET SESSION time_zone = '" + zone + "'");
        return new HikariDataSource(config);
    }

    private static HikariConfig config(String jdbcUrl, String username, String password) {
        PrivacyJournalProperties properties = new PrivacyJournalProperties();
        properties.getDatasource().setUrl(jdbcUrl);
        properties.getDatasource().setUsername(username);
        properties.getDatasource().setPassword(password);
        properties.getDatasource().setConnectionTimeout(Duration.ofSeconds(10));
        return PrivacyJournalConnections.poolConfig(properties);
    }
}
