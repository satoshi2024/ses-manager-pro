package com.ses.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ses.common.util.SecurityUtils;
import com.ses.service.security.DataScopeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;

/**
 * ダッシュボード集計のキャッシュ設定。
 *
 * <p>ダッシュボードは1リクエストで十数本のクエリを撃ち、うち数本は要員・契約の全件ロードを
 * 伴うため、同時アクセス時に最も重い画面になる。中身はKPIの集計値で秒単位の鮮度は不要なので、
 * 短いTTLでキャッシュして繰り返しの再集計を止める。
 *
 * <p>TTL は {@code app.cache.dashboard-ttl-seconds}（既定60秒）。0以下でキャッシュ無効。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** ダッシュボードKPI・チャートの集計結果。 */
    public static final String CACHE_DASHBOARD_SUMMARY = "dashboardSummary";
    /** 将来稼働率・Bench予測。 */
    public static final String CACHE_UTILIZATION_FORECAST = "utilizationForecast";

    @Bean
    public CacheManager cacheManager(
            @Value("${app.cache.dashboard-ttl-seconds:60}") long ttlSeconds,
            @Value("${app.cache.dashboard-max-size:500}") long maxSize) {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                CACHE_DASHBOARD_SUMMARY, CACHE_UTILIZATION_FORECAST);
        if (ttlSeconds <= 0) {
            // 0以下は「キャッシュしない」。設定だけで無効化できるようにしておく。
            manager.setCaffeine(Caffeine.newBuilder().maximumSize(0));
        } else {
            manager.setCaffeine(Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                    .maximumSize(maxSize));
        }
        return manager;
    }

    /**
     * データスコープを織り込んだキャッシュキーを作る。
     *
     * <p><b>これがこのキャッシュの安全性の要。</b> ダッシュボードの集計は
     * {@link DataScopeService} により閲覧者ごとに母集団が変わる（担当限定の営業は自分の担当分だけ）。
     * メソッド引数だけをキーにすると、担当限定ユーザーの結果を別のユーザーへ配ってしまう。
     * スコープ非適用のユーザー（管理者・マネージャー等）は全員が同一母集団なので "ALL" で共有し、
     * スコープ適用中のユーザーだけ個別キーにする。
     */
    @Bean("dashboardScopeKeyGenerator")
    public KeyGenerator dashboardScopeKeyGenerator(ObjectProvider<DataScopeService> dataScopeProvider) {
        return (target, method, params) -> {
            DataScopeService scope = dataScopeProvider.getIfAvailable();
            String scopeKey;
            if (scope != null && scope.isScoped()) {
                Long uid = SecurityUtils.currentUserId();
                // ユーザーID が取れない場合は共有させない（安全側に倒す）
                scopeKey = "U" + (uid != null ? uid : "unknown-" + System.identityHashCode(target));
            } else {
                scopeKey = "ALL";
            }
            return method.getName() + ":" + scopeKey + ":" + Arrays.deepToString(params);
        };
    }
}
