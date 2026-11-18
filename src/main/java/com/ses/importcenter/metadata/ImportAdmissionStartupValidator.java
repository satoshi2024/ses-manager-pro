package com.ses.importcenter.metadata;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * 本番起動時のNF-06 admission承認設定の検査（Q-5）。
 *
 * <ul>
 *   <li>NF-06スイッチがOFF：起動を止めない（初回リリースはOFFで配備するため）。部分的な設定があればWARNだけを出す。
 *       OFFの間は取込APIが404になり、admissionにも到達しない。</li>
 *   <li>NF-06スイッチがON：承認設定に構造的な不備（未設定・部分設定・形式不正・placeholder・digest不一致・有効期間の
 *       上限超過等）があれば起動を失敗させる。設定誤りを最初の利用者requestまで潜伏させないためである。</li>
 *   <li>NF-06スイッチがONで、不備が時間の経過だけ（署名日前・失効）：起動は止めず、取込の受付だけを拒否したまま
 *       WARNを出す（{@value #LOG_CODE_STOPPED}）。承認の失効で無関係な機能まで再起動のたびに使えなくなる事態を避ける。
 *       admission portは要求ごとに評価し直すため、受付は自動では再開せず、再承認後に設定を差し替えて再起動する。</li>
 * </ul>
 * メッセージには固定の違反コードだけを出し、設定値は出さない。
 */
@Component
@Profile("prod")
public class ImportAdmissionStartupValidator {
    /** 時間要因で取込受付を止めたまま起動したことを示す固定コード（監視のlog検索用）。 */
    public static final String LOG_CODE_STOPPED = "NF06_IMPORT_ADMISSION_STOPPED_AT_STARTUP";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportAdmissionStartupValidator.class);

    private final ImportAdmissionApprovalProperties properties;
    private final ObjectProvider<FeatureSwitches> featureSwitches;
    private final Clock clock;

    @Autowired
    public ImportAdmissionStartupValidator(ImportAdmissionApprovalProperties properties,
                                           ObjectProvider<FeatureSwitches> featureSwitches) {
        this(properties, featureSwitches, Clock.systemUTC());
    }

    ImportAdmissionStartupValidator(ImportAdmissionApprovalProperties properties,
                                    ObjectProvider<FeatureSwitches> featureSwitches, Clock clock) {
        this.properties = properties;
        this.featureSwitches = featureSwitches;
        this.clock = clock;
    }

    @PostConstruct
    void validate() {
        FeatureSwitches switches = featureSwitches == null ? null : featureSwitches.getIfAvailable();
        boolean enabled = switches != null && switches.isEnabled(Feature.NF06);
        ImportAdmissionApproval.Evaluation evaluation =
                ImportAdmissionApproval.evaluate(properties, ImportAdmissionApproval.businessDate(clock));
        if (evaluation.complete()) {
            log.info("[取込] NF-06 admission承認設定を確認しました: nf06Enabled={} decisionId={} sourceCodeCount={}",
                    enabled, evaluation.snapshot().decisionId(), evaluation.snapshot().sourceCodes().size());
            return;
        }
        if (!enabled) {
            if (ImportAdmissionApproval.configured(properties)) {
                log.warn("[取込] NF-06 admission承認設定が不完全ですが、NF-06がOFFのため起動を継続します（取込は拒否）: "
                        + "violations={}", String.join(",", evaluation.violations()));
            }
            return;
        }
        List<String> blocking = ImportAdmissionApproval.structuralViolations(evaluation);
        if (!blocking.isEmpty()) {
            throw new IllegalStateException("本番のNF-06取込admission承認設定が完全でないため起動できません: "
                    + String.join(",", evaluation.violations()));
        }
        log.warn("{}: NF-06取込admissionの承認が有効期間外のため取込受付を停止して起動します。再承認後に再起動してください: "
                + "violations={}", LOG_CODE_STOPPED, String.join(",", evaluation.violations()));
    }
}
