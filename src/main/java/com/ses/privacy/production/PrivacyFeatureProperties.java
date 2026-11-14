package com.ses.privacy.production;

import com.ses.config.feature.FeatureSwitchProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NF07 の公開スイッチ（{@code privacy.*}）。production-bridge-phased-plan.md §5.1。
 *
 * <p>既定は全てOFF。真偽値として解釈できない値（例: {@code ture}）はbind時に失敗し、アプリは起動しない
 * （{@link FeatureSwitchProperties} と同じ扱い）。{@code privacy.retention.enabled} は機能スイッチ
 * {@code Feature.NF07_RETENTION} の設定値でもあり、{@link FeatureSwitchProperties#toggle} は本classの
 * {@link #getRetention()} をそのまま返す（{@code app.features.*} に別名は作らない）。
 * {@code privacy.dsar.enabled} は W3-14 では設定値だけを持ち、入口と機能スイッチへの登録は A2a で行う（C-1）。
 *
 * <p>スイッチは公開可否であって権限ではない。ON でも {@code privacy.*} の action 権限（privacy-admin）が別に要る。
 */
@Data
@Component
@ConfigurationProperties(prefix = "privacy")
public class PrivacyFeatureProperties {

    /** NF07 保持・保全（policy説明・catalog・hold）。 */
    private Retention retention = new Retention();

    /** NF07 DSAR（本人請求）。W3-14 では入口を持たない。 */
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
    }
}
