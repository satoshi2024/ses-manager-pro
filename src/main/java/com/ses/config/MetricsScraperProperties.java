package com.ses.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Prometheus スクレイパー用マシン認証設定（app.metrics.scraper.*）。
 * 秘密情報は環境変数経由のみ。コード・設定ファイルへ平文シークレットを埋め込まない。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.metrics.scraper")
public class MetricsScraperProperties {

    /**
     * スクレイパー Basic 認証を有効化するフラグ。
     * 実ユーザー Bean の登録条件は {@link #credentialsConfigured()} も参照。
     */
    private boolean enabled = false;

    /** スクレイパーユーザー名（METRICS_SCRAPER_USERNAME）。空なら登録しない。 */
    private String username = "";

    /** スクレイパーパスワード（METRICS_SCRAPER_PASSWORD）。空なら登録しない。 */
    private String password = "";

    /** username / password が両方とも非空のとき true。 */
    public boolean credentialsConfigured() {
        return StringUtils.hasText(username) && StringUtils.hasText(password);
    }
}
