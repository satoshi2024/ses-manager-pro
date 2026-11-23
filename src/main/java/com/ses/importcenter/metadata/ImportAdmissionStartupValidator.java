package com.ses.importcenter.metadata;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 本番起動時のNF-06 admission承認の値の確認（Q-5）。起動は止めない（feature-enablement-console design §8.3、requirements §7 の
 * 「機能を閉じる」。FE-B02）。
 *
 * <p>以前は NF-06 がONで承認設定に構造的な不備があれば起動を失敗させ、時間要因（署名日前・失効）だけなら起動して取込の受付だけを
 * 拒否していた。機能の開放では、同じ判定（{@link ImportAdmissionApproval#evaluate}）を profile に依らない評価器
 * {@code ImportCenterEnablementConditions} が使い、条件が成り立たなければ NF-06 を開けない・開いていれば起動時・定期の評価で
 * 自動閉鎖する（期間の外も NF-06 全体を閉じる。D-8・J-09）。本classは起動の log に評価の結果を出すだけで、値（決定ID 以外）を出さない。
 * 承認の値は源（{@link ImportAdmissionApprovalSource}。prod は確認済みの署名 {@code NF06_ADMISSION}）から読む。</p>
 */
@Component
@Profile("prod")
public class ImportAdmissionStartupValidator {
    /** 起動時に NF-06 が開いているのに承認が成り立たない（起動時の評価で閉じる）ことを示す固定コード（監視のlog検索用）。 */
    public static final String LOG_CODE_STOPPED = "NF06_IMPORT_ADMISSION_STOPPED_AT_STARTUP";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportAdmissionStartupValidator.class);

    private final ImportAdmissionApprovalSource source;
    private final ObjectProvider<FeatureSwitches> featureSwitches;
    private final Clock clock;

    @Autowired
    public ImportAdmissionStartupValidator(ImportAdmissionApprovalSource source,
                                           ObjectProvider<FeatureSwitches> featureSwitches) {
        this(source, featureSwitches, Clock.systemUTC());
    }

    ImportAdmissionStartupValidator(ImportAdmissionApprovalProperties properties,
                                    ObjectProvider<FeatureSwitches> featureSwitches, Clock clock) {
        this(new ImportAdmissionApprovalSource.Property(properties), featureSwitches, clock);
    }

    ImportAdmissionStartupValidator(ImportAdmissionApprovalSource source,
                                    ObjectProvider<FeatureSwitches> featureSwitches, Clock clock) {
        this.source = source == null ? new ImportAdmissionApprovalSource.Property(null) : source;
        this.featureSwitches = featureSwitches;
        this.clock = clock;
    }

    @PostConstruct
    void validate() {
        FeatureSwitches switches = featureSwitches == null ? null : featureSwitches.getIfAvailable();
        boolean enabled = switches != null && switches.isEnabled(Feature.NF06);
        ImportAdmissionApprovalProperties properties = source.current();
        ImportAdmissionApproval.Evaluation evaluation =
                ImportAdmissionApproval.evaluate(properties, ImportAdmissionApproval.businessDate(clock));
        if (evaluation.complete()) {
            log.info("[取込] NF-06 admission承認設定を確認しました: nf06Enabled={} decisionId={} sourceCodeCount={}",
                    enabled, evaluation.snapshot().decisionId(), evaluation.snapshot().sourceCodes().size());
            return;
        }
        if (!enabled) {
            if (ImportAdmissionApproval.configured(properties)) {
                log.warn("[取込] NF-06 admission承認設定が不完全です（NF-06は閉じているため取込は拒否のまま）: violations={}",
                        String.join(",", evaluation.violations()));
            }
            return;
        }
        log.warn("{}: NF-06取込admissionの承認が成り立たないため、取込の受付を拒否し、起動時の評価でNF-06を閉じます"
                + "（起動は止めません。再承認の後に画面で申請と承認をやり直してください）: violations={}",
                LOG_CODE_STOPPED, String.join(",", evaluation.violations()));
    }
}
