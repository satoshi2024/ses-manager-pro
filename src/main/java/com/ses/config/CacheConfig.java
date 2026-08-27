package com.ses.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.ses.common.util.SecurityUtils;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
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
     * データスコープおよび組織スコープを織り込んだキャッシュキーを作る。
     *
     * <p><b>これがこのキャッシュの安全性の要。</b> ダッシュボードの集計は
     * {@link DataScopeService} および {@link OrganizationScopeService} により
     * 閲覧者ごとに母集団が変わる。メソッド引数だけをキーにすると、スコープ適用中ユーザーの
     * 結果を別のユーザーへ配ってしまう。
     *
     * <p>いずれかのスコープが適用中のユーザーはユーザーIDで個別キーにし、
     * 両スコープ非適用のユーザー（管理者等）は全員が同一母集団なので "ALL" で共有する。
     */
    @Bean("dashboardScopeKeyGenerator")
    public KeyGenerator dashboardScopeKeyGenerator(
            ObjectProvider<DataScopeService> dataScopeProvider,
            ObjectProvider<OrganizationScopeService> orgScopeProvider,
            ObjectProvider<com.ses.service.security.ScopeVersionRegistry> scopeVersionProvider) {
        return (target, method, params) -> {
            DataScopeService dataScope = dataScopeProvider.getIfAvailable();
            OrganizationScopeService orgScope = orgScopeProvider.getIfAvailable();
            boolean dataScopeActive = dataScope != null && dataScope.isScoped();
            boolean orgScopeActive = orgScope != null && !orgScope.hasFullAccess();
            String scopeKey;
            if (dataScopeActive || orgScopeActive) {
                Long uid = SecurityUtils.currentUserId();
                if (uid != null) {
                    scopeKey = "U" + uid;
                } else {
                    // 外部subjectをローカルIDへ解決できないprincipalは共有キャッシュへ入れない。
                    // usernameはprincipalごとの安定した境界として使い、usernameも無い場合は
                    // リクエストごとに一意化して必ずcache hitを防ぐ。
                    String username = SecurityUtils.currentUsername();
                    scopeKey = username == null || username.isBlank()
                            ? "UNCACHEABLE-" + java.util.UUID.randomUUID()
                            : "P" + username;
                }
            } else {
                scopeKey = "ALL";
            }
            // schedulerの保存済みscopeは同一userの現在scopeと別cache境界にする。
            com.ses.dto.report.ReportScopeSnapshot savedScope =
                    com.ses.service.security.ReportScopeContext.current();
            if (savedScope != null && savedScope.getHash() != null) {
                scopeKey += ":R" + savedScope.getHash();
            }
            // 同一ユーザーでも所属・組織階層・DataScope・担当が変わればキーを変える。
            // これが無いと権限縮小後もTTLが切れるまで旧scopeの集計が返る。
            com.ses.service.security.ScopeVersionRegistry scopeVersion = scopeVersionProvider.getIfAvailable();
            long generation = scopeVersion == null ? 0L : scopeVersion.current();
            return method.getName() + ":" + scopeKey + ":v" + generation + ":" + Arrays.deepToString(params);
        };
    }
}
