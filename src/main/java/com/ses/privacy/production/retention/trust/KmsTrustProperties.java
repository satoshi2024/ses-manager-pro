package com.ses.privacy.production.retention.trust;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.StringUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * NF07 の trust（D-03 = AWS KMS 非対称署名、鍵 version 単位の失効）の設定（{@code privacy.trust.kms.*}）。
 *
 * <p>{@link #getAnchorFile()} が空（既定）なら trust は第1期と同じ {@link UnconfiguredPrivacyTrustSource}（anchor 0件・失効状態は
 * 常に UNKNOWN）で、登録・有効化と hold 解除の入口は404のまま。設定すると {@link KmsPrivacyTrustSource} が、その file の
 * trust 文書（{@link KmsTrustDocument}）を判定のたびに（鮮度の上限まで cache して）読む。値は秘密ではないが、file の書込みは
 * D-03 owner の手順だけに限る（file の内容が trust の正本になる）。AI・実装者は値を設定しない。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "privacy.trust.kms")
public class KmsTrustProperties {

    /** 文書の鮮度の上限の下限。 */
    public static final Duration MIN_MAX_DOCUMENT_AGE = Duration.ofMinutes(5);
    /** 文書の鮮度の上限の上限。 */
    public static final Duration MAX_MAX_DOCUMENT_AGE = Duration.ofDays(31);
    /** 再読込の間隔の既定。 */
    public static final Duration DEFAULT_RELOAD_INTERVAL = Duration.ofSeconds(30);
    /** 再読込の間隔の下限。 */
    public static final Duration MIN_RELOAD_INTERVAL = Duration.ofSeconds(1);
    /** 再読込の間隔の上限。 */
    public static final Duration MAX_RELOAD_INTERVAL = Duration.ofMinutes(5);

    /** trust 文書の file の絶対 path（{@code PRIVACY_TRUST_KMS_ANCHOR_FILE}）。空なら KMS の trust を使わない。 */
    private String anchorFile;

    /**
     * 文書の鮮度の上限（{@code PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE}。単位の無い数値は秒）。文書の {@code generatedAt} から
     * この時間を過ぎると、失効状態は全て UNKNOWN になる（D-03 owner の文書の更新が止まったことを、失効を確認できない状態として
     * 扱う）。5分〜31日。範囲外・未設定なら常に UNKNOWN。既定は無い（D-03 で決める）。
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration maxDocumentAge;

    /**
     * 文書の最終更新日時と大きさが変わらない間、読んだ内容を使い続ける最長の時間（{@code PRIVACY_TRUST_KMS_RELOAD_INTERVAL}。
     * 単位の無い数値は秒）。file の最終更新日時・大きさは判定のたびに確かめ、変われば次の判定で読み直す。1秒〜5分。
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration reloadInterval = DEFAULT_RELOAD_INTERVAL;

    /** KMS の trust を使う設定か（{@link #anchorFile} が空でない）。 */
    public boolean isConfigured() {
        return StringUtils.hasText(anchorFile);
    }

    /** 鮮度の上限が範囲内か。 */
    boolean isMaxDocumentAgeValid() {
        return maxDocumentAge != null && maxDocumentAge.compareTo(MIN_MAX_DOCUMENT_AGE) >= 0
                && maxDocumentAge.compareTo(MAX_MAX_DOCUMENT_AGE) <= 0;
    }

    /** 再読込の間隔が範囲内か。 */
    boolean isReloadIntervalValid() {
        return reloadInterval != null && reloadInterval.compareTo(MIN_RELOAD_INTERVAL) >= 0
                && reloadInterval.compareTo(MAX_RELOAD_INTERVAL) <= 0;
    }

    /** 文書の path（絶対 path でなければ null）。 */
    Path anchorPath() {
        if (!isConfigured()) {
            return null;
        }
        try {
            Path path = Path.of(anchorFile.trim());
            return path.isAbsolute() ? path : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 設定の構造的な違反（固定の code。値は含めない）。{@link #isConfigured()} が false なら空。 */
    List<String> violations() {
        List<String> violations = new ArrayList<>();
        if (!isConfigured()) {
            return violations;
        }
        if (anchorPath() == null) {
            violations.add("trust.kms.anchorFile.notAbsolute");
        }
        if (maxDocumentAge == null) {
            violations.add("trust.kms.maxDocumentAge.unset");
        } else if (!isMaxDocumentAgeValid()) {
            violations.add("trust.kms.maxDocumentAge.outOfRange");
        }
        if (!isReloadIntervalValid()) {
            violations.add("trust.kms.reloadInterval.outOfRange");
        }
        return violations;
    }

    @Override
    public String toString() {
        return "KmsTrustProperties{configured=" + isConfigured() + ", maxDocumentAge=" + maxDocumentAge
                + ", reloadInterval=" + reloadInterval + "}";
    }
}
