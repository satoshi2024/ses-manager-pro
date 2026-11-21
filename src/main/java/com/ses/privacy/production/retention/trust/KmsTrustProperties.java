package com.ses.privacy.production.retention.trust;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
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
 *
 * <p>時間の値は文字列で bind し、使うときに解釈する（{@code 24h}・{@code PT24H}・単位の無い数値は秒）。解釈できない値
 * （例: {@code 1 day}）でも bind は失敗させない: NF07 の flag が OFF なら何も検査しない規則（feature-switches 4.7.2）に合わせ、
 * 起動を止めるのは flag が ON の prod の起動検査（{@link PrivacyTrustStartupValidator}、違反コード {@code *.invalid}）だけにする
 * （RB-NF07-TRUST-KMS review P3-6）。実行中の扱いは範囲外と同じ（鮮度の上限なら常に UNKNOWN・anchor 0件、再読込の間隔なら既定）。
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
     * 文書の鮮度の上限（{@code PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE}。{@code 24h}・{@code PT24H}、単位の無い数値は秒）。文書の
     * {@code generatedAt} からこの時間を過ぎると、anchor は0件・失効状態は全て UNKNOWN になる（D-03 owner の文書の更新が止まった
     * ことを、失効を確認できない状態として扱う）。5分〜31日。未設定・解釈できない・範囲外なら常にその状態。既定は無い（D-03 で
     * 決める）。
     */
    private String maxDocumentAge;

    /**
     * 文書の最終更新日時と大きさが変わらない間、読んだ内容を使い続ける最長の時間（{@code PRIVACY_TRUST_KMS_RELOAD_INTERVAL}。
     * 単位の無い数値は秒）。file の最終更新日時・大きさは判定のたびに確かめ、変われば次の判定で読み直す。1秒〜5分。空なら既定。
     */
    private String reloadInterval;

    /** KMS の trust を使う設定か（{@link #anchorFile} が空でない）。 */
    public boolean isConfigured() {
        return StringUtils.hasText(anchorFile);
    }

    /** 鮮度の上限（未設定・解釈できない・範囲外なら null）。 */
    Duration maxDocumentAgeValue() {
        Duration value = parse(maxDocumentAge);
        return value != null && value.compareTo(MIN_MAX_DOCUMENT_AGE) >= 0 && value.compareTo(MAX_MAX_DOCUMENT_AGE) <= 0
                ? value : null;
    }

    /** 再読込の間隔（空なら既定。解釈できない・範囲外も既定）。 */
    Duration reloadIntervalValue() {
        if (!StringUtils.hasText(reloadInterval)) {
            return DEFAULT_RELOAD_INTERVAL;
        }
        Duration value = parse(reloadInterval);
        return value != null && value.compareTo(MIN_RELOAD_INTERVAL) >= 0 && value.compareTo(MAX_RELOAD_INTERVAL) <= 0
                ? value : DEFAULT_RELOAD_INTERVAL;
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
        if (!StringUtils.hasText(maxDocumentAge)) {
            violations.add("trust.kms.maxDocumentAge.unset");
        } else if (parse(maxDocumentAge) == null) {
            violations.add("trust.kms.maxDocumentAge.invalid");
        } else if (maxDocumentAgeValue() == null) {
            violations.add("trust.kms.maxDocumentAge.outOfRange");
        }
        if (StringUtils.hasText(reloadInterval)) {
            Duration value = parse(reloadInterval);
            if (value == null) {
                violations.add("trust.kms.reloadInterval.invalid");
            } else if (value.compareTo(MIN_RELOAD_INTERVAL) < 0 || value.compareTo(MAX_RELOAD_INTERVAL) > 0) {
                violations.add("trust.kms.reloadInterval.outOfRange");
            }
        }
        return violations;
    }

    /** {@code 24h}・{@code PT24H}・単位の無い数値（秒）を解釈する（空・解釈できなければ null）。 */
    static Duration parse(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return DurationStyle.detectAndParse(value.trim(), ChronoUnit.SECONDS);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "KmsTrustProperties{configured=" + isConfigured() + ", maxDocumentAge=" + maxDocumentAge
                + ", reloadInterval=" + reloadInterval + "}";
    }
}
