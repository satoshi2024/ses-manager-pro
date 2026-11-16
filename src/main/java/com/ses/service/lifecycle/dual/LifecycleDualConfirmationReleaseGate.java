package com.ses.service.lifecycle.dual;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 二者確認の子スイッチ（{@link Feature#NF01_DUAL_CONFIRMATION}）を本番でONにする前の署名gate
 * （NF01細粒度epoch計画 §7.8 開始条件・§10 Privacy・§12 O-8、主控の判断）。
 *
 * <p>二者確認は提出・確認の業務comment（{@code t_lifecycle_dual_submission.business_comment}・
 * {@code t_lifecycle_dual_confirmation.decision_comment}）を追記専用表へ保存する。これらの保存期間と削除・匿名化の経路は
 * Privacyの署名で決まる（O-8）。実装は署名前に出荷し（子スイッチOFF）、commentを実際に書き込む＝子スイッチをONにするのは
 * 署名後とする。本classはprod profileで、子スイッチの実効値がONなのに署名の参照
 * （{@code app.features.nf01-dual-confirmation.privacy-sign-off-ref}）が無い・形式が不正・placeholderのままの場合に
 * 起動を失敗させる。手順は {@code .kiro/specs/production-launch/feature-switches.md} 4.9。</p>
 *
 * <ul>
 *   <li>子スイッチの実効値がOFF（親NF-01がOFFの場合を含む）: 何もしない。初回配備はOFFで行う。</li>
 *   <li>実効値がON: 参照が {@link #SIGN_OFF_REF} に一致し、placeholderでないことを要求する。満たさなければ
 *       {@link IllegalStateException} で起動を止める（設定誤りを最初の提出まで潜伏させない）。</li>
 * </ul>
 * <p>参照値は秘密情報ではないため起動logに出す（監査で署名と配備を突き合わせるため）。</p>
 */
@Slf4j
@Component
@Profile("prod")
public class LifecycleDualConfirmationReleaseGate {

    /** 起動を止めた理由を示す固定コード（監視のlog検索用）。 */
    public static final String VIOLATION_CODE = "NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_MISSING";

    /** 署名の参照の形式（英数字で始まる4〜128文字。review文書のID・ticket番号等）。 */
    static final Pattern SIGN_OFF_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{3,127}");

    /**
     * 一部にでも含めば署名の参照とみなさない語（小文字で比較。W3-21a review P3-6: 完全一致だけでは {@code TODO-1}・
     * {@code TBD2026}・{@code xxxxx} が通った）。
     */
    private static final List<String> PLACEHOLDER_FRAGMENTS = List.of("todo", "tbd", "xxx", "placeholder", "changeme",
            "change-me", "change_me", "dummy", "fixme", "sample", "example", "unsigned", "replace", "your-");

    /** この語で始まる値は署名の参照とみなさない（小文字で比較）。 */
    private static final List<String> PLACEHOLDER_PREFIXES = List.of("test", "pending", "tmp", "temp", "none", "null",
            "n/a", "dev", "local");

    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");

    private final ObjectProvider<FeatureSwitches> featureSwitches;
    private final FeatureSwitchProperties properties;

    public LifecycleDualConfirmationReleaseGate(ObjectProvider<FeatureSwitches> featureSwitches,
                                                FeatureSwitchProperties properties) {
        this.featureSwitches = featureSwitches;
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        FeatureSwitches switches = featureSwitches == null ? null : featureSwitches.getIfAvailable();
        if (switches == null || !switches.isEnabled(Feature.NF01_DUAL_CONFIRMATION)) {
            return;
        }
        String reference = signOffReference();
        if (!acceptable(reference)) {
            throw new IllegalStateException(VIOLATION_CODE + ": 二者確認（NF01_DUAL_CONFIRMATION）をONにするには"
                    + " Privacy署名の参照（app.features.nf01-dual-confirmation.privacy-sign-off-ref）が必要です");
        }
        log.info("[二者確認] Privacy署名の参照を確認して公開します: privacySignOffRef={}", reference);
    }

    /**
     * 署名の参照として受け付ける値か。null・空・前後の空白・形式不正（{@link #SIGN_OFF_REF}）、英字と数字の両方を含まない値、
     * placeholderの語を一部に含む値・placeholderの語で始まる値は不可。
     */
    static boolean acceptable(String reference) {
        if (reference == null) {
            return false;
        }
        String trimmed = reference.trim();
        if (!trimmed.equals(reference) || !SIGN_OFF_REF.matcher(trimmed).matches()
                || !HAS_LETTER.matcher(trimmed).matches() || !HAS_DIGIT.matcher(trimmed).matches()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_FRAGMENTS.stream().noneMatch(lower::contains)
                && PLACEHOLDER_PREFIXES.stream().noneMatch(lower::startsWith);
    }

    private String signOffReference() {
        if (properties == null) {
            return null;
        }
        FeatureSwitchProperties.Toggle toggle = properties.toggle(Feature.NF01_DUAL_CONFIRMATION);
        return toggle instanceof FeatureSwitchProperties.DualConfirmationToggle dual ? dual.getPrivacySignOffRef() : null;
    }
}
