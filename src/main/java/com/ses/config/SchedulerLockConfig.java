package com.ses.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * バッチ（{@code @Scheduled}）の多重起動防止。
 *
 * <p>複数インスタンスで運用すると、同じ cron が全インスタンスで同時に発火する。
 * 通知系は dedupe_key のユニーク制約で二重登録こそ防げるが、契約更新ドラフト生成は
 * 「既存ドラフトの有無を見てから INSERT」する構造のため、同時実行すると
 * 一意制約違反side で毎日「契約更新ドラフト作成エラー」通知が飛ぶ。
 * DBを共有ロックに使い、各バッチを1インスタンスのみで実行させる。
 *
 * <p>ロック行は {@code shedlock} テーブル（V58）。単一インスタンス運用でも
 * 動作は変わらない（常にロックを取得できる）ため、構成によらず有効化しておく。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(
            DataSource dataSource,
            @Value("${app.scheduler.lock.use-db-time:true}") boolean useDbTime) {
        JdbcTemplateLockProvider.Configuration.Builder builder = JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource));
        if (useDbTime) {
            // 本番MySQLではDBサーバ時刻を使い、インスタンス間の時刻ずれでロックが早期解放されるのを防ぐ。
            builder.usingDbTime();
        }
        // test profileのH2ではJVM時刻を使う。H2/プロキシDataSourceが未知のDB productを返す場合、
        // ShedLock 5.10.2のusingDbTime()はUnsupportedOperationExceptionを投げるためである。
        return new JdbcTemplateLockProvider(builder.build());
    }
}
