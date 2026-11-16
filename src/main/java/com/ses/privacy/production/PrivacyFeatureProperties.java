package com.ses.privacy.production;

import com.ses.config.feature.FeatureSwitchProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * NF07 の公開スイッチ（{@code privacy.*}）。production-bridge-phased-plan.md §5.1。
 *
 * <p>既定は全てOFF。真偽値として解釈できない値（例: {@code ture}）はbind時に失敗し、アプリは起動しない
 * （{@link FeatureSwitchProperties} と同じ扱い）。{@code privacy.retention.enabled} は機能スイッチ
 * {@code Feature.NF07_RETENTION}、{@code privacy.dsar.enabled} は {@code Feature.NF07_DSAR}（A2a、C-1）の設定値でもあり、
 * {@link FeatureSwitchProperties#toggle} は本classの {@link #getRetention()}・{@link #getDsar()} をそのまま返す
 * （{@code app.features.*} に別名は作らない）。
 *
 * <p>スイッチは公開可否であって権限ではない。ON でも {@code privacy.*} の action 権限（privacy-admin）が別に要る。
 */
@Data
@Component
@ConfigurationProperties(prefix = "privacy")
public class PrivacyFeatureProperties {

    /** NF07 保持・保全（policy説明・catalog・hold）。 */
    private Retention retention = new Retention();

    /** NF07 DSAR（本人請求）。入口は A2a の {@code /privacy/dsar}・{@code /api/privacy/dsar}。 */
    private Dsar dsar = new Dsar();

    /** {@code privacy.retention.*}。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @ToString(callSuper = true)
    public static class Retention extends FeatureSwitchProperties.Toggle {
    }

    /** {@code privacy.dsar.*}。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @ToString(callSuper = true)
    public static class Dsar extends FeatureSwitchProperties.Toggle {

        /** 期限の通知を出し始める日数の既定（運用上の値で、法的な期限ではない）。 */
        public static final int DEFAULT_DEADLINE_WARNING_DAYS = 7;
        /** 期限の通知を出し始める日数の上限。 */
        public static final int MAX_DEADLINE_WARNING_DAYS = 90;

        /**
         * D-06（DSAR SOP）の期限規則: 受付日（JST）から期限日までの暦日数（{@code privacy.dsar.due-days}）。
         * 法的な値のため既定値を持たない（I-1）。未設定・範囲外なら期限日を計算しない（{@code due_date} は NULL）。
         * 整数として解釈できない値は bind 時に失敗し、起動しない。
         */
        private Integer dueDays;

        /** 期限の何日前から privacy-admin へ通知するか（{@code privacy.dsar.deadline-warning-days}。1〜90 に丸める）。 */
        private int deadlineWarningDays = DEFAULT_DEADLINE_WARNING_DAYS;

        /**
         * D-06 で決める開示 package の最長保存期間（{@code privacy.dsar.package-max-age}。NF07-A2b）。法的な値のため既定値を
         * 持たない（I-1）。未設定・範囲外なら草案 package を作らない。単位の無い数値は日数として解釈する（{@code 30} は30日。
         * {@code PT12H}・{@code 12h} も受け付ける）。解釈できない値は bind 時に失敗し、起動しない。
         */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration packageMaxAge;
    }
}
