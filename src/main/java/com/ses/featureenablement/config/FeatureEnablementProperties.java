package com.ses.featureenablement.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 機能の開放の運用値（{@code app.feature-enablement.*}。feature-enablement-console design §3.1・§3.3・§7.2・§10）。
 *
 * <p>範囲は bind の時に検査し、外れていれば起動を止める（設定の誤り。機能の条件ではない）: {@code refresh-interval} は 1秒〜30秒で
 * {@code max-staleness} の半分より短い、{@code max-staleness} は 10秒〜300秒、{@code evaluation-interval} は 1分〜60分、
 * {@code journal-reverify-interval} は 1分〜60分。総スイッチの原文の文字列の検査（{@code true}/{@code false} だけ）と、prod で
 * これらの運用値を配備の設定で上書きすることの拒否は {@code FeatureEnablementProductionValidator}（FE-A08）が行う。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.feature-enablement")
public class FeatureEnablementProperties implements InitializingBean {

    /** 開放状態の源。dev・test の既定は {@code properties}、prod は {@code database}（prod では他を拒否する。FE-A08）。 */
    private Source source = Source.PROPERTIES;

    /** 緊急の総スイッチ（{@code FEATURE_ENABLEMENT_ALLOWED}、既定 true）。閉なら全ての開放単位の実効値を閉じる。 */
    private boolean allowed = true;

    /** 他の instance の変更を拾う世代の poll の間隔（既定5秒、1秒〜30秒）。 */
    private Duration refreshInterval = Duration.ofSeconds(5);

    /** 最後に DB を読めた時刻からこの時間を超えたら全ての開放単位を閉じる（既定60秒、10秒〜300秒。fail-closed）。 */
    private Duration maxStaleness = Duration.ofSeconds(60);

    /** 定期の条件の評価の間隔（既定5分、1分〜60分）。評価できない状態が3倍続けば自動閉鎖する（J-10）。 */
    private Duration evaluationInterval = Duration.ofMinutes(5);

    /** Journal の権限の再検査の間隔の下限（既定10分、1分〜60分。段階 C）。 */
    private Duration journalReverifyInterval = Duration.ofMinutes(10);

    /**
     * 定期の評価の初回の遅延（既定1分、0〜60分。起動時の評価は {@code FeatureEnablementStartupEvaluator} が web server の前に行うため、
     * 起動の直後に重ねて評価しない。{@code @Scheduled(initialDelayString)} が同じ key を読む。review rb-fe-a1 P3-5）。
     */
    private Duration evaluationInitialDelay = Duration.ofMinutes(1);

    /** 終わった署名の消去の cron（既定 {@code 0 40 3 * * *}、Asia/Tokyo。{@code @Scheduled(cron)} が同じ key を読む）。 */
    private String signOffPurgeCron = "0 40 3 * * *";

    /** 世代の poll の専用 thread を起動するか（試験では起動せず、試験が明示的に読み直す。design §13）。 */
    private boolean refreshThreadEnabled = true;

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /** 範囲と相互の関係を検査する（外れていれば {@link IllegalStateException}。値は message に出さない）。 */
    public void validate() {
        if (source == null) {
            throw new IllegalStateException("app.feature-enablement.source は properties か database を指定してください");
        }
        requireRange("app.feature-enablement.refresh-interval", refreshInterval, Duration.ofSeconds(1),
                Duration.ofSeconds(30));
        requireRange("app.feature-enablement.max-staleness", maxStaleness, Duration.ofSeconds(10),
                Duration.ofSeconds(300));
        requireRange("app.feature-enablement.evaluation-interval", evaluationInterval, Duration.ofMinutes(1),
                Duration.ofMinutes(60));
        requireRange("app.feature-enablement.journal-reverify-interval", journalReverifyInterval,
                Duration.ofMinutes(1), Duration.ofMinutes(60));
        requireRange("app.feature-enablement.evaluation-initial-delay", evaluationInitialDelay, Duration.ZERO,
                Duration.ofMinutes(60));
        if (signOffPurgeCron == null || !org.springframework.scheduling.support.CronExpression.isValidExpression(
                signOffPurgeCron)) {
            throw new IllegalStateException("app.feature-enablement.sign-off-purge-cron は cron の式で指定してください");
        }
        if (refreshInterval.multipliedBy(2).compareTo(maxStaleness) >= 0) {
            throw new IllegalStateException(
                    "app.feature-enablement.refresh-interval は max-staleness の半分より短くしてください");
        }
    }

    /** DB の源か。 */
    public boolean databaseSource() {
        return source == Source.DATABASE;
    }

    private static void requireRange(String name, Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalStateException(name + " の範囲が不正です（" + min + "〜" + max + "）");
        }
    }

    /** 開放状態の源。 */
    public enum Source { PROPERTIES, DATABASE }
}
