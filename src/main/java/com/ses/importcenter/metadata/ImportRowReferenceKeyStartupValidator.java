package com.ses.importcenter.metadata;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本番起動時のNF-06行参照鍵（{@value ImportSourceRowReference#PROPERTY}）の検査（W3-12。W3-05 review P2-1）。
 * {@link ImportAdmissionStartupValidator} と同じ考え方で fail-closed にする。
 *
 * <ul>
 *   <li>NF-06スイッチがOFF：起動を止めない（初回リリースはOFFで配備する）。鍵が設定されているのに使えない
 *       （形式不正・開発用鍵）場合だけWARNを出す。OFFの間はpreview・error exportに到達しない。</li>
 *   <li>NF-06スイッチがON：鍵が未設定・形式不正・公開済みの鍵（開発用の既定鍵・test用の鍵。表記によらず復号後の
 *       byte列で判定）なら起動を失敗させる。設定漏れを最初の
 *       preview要求（503）まで潜伏させないためである。</li>
 * </ul>
 * messageには鍵の状態（固定値）だけを出し、鍵の値は出さない。
 */
@Component
@Profile("prod")
public class ImportRowReferenceKeyStartupValidator {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ImportRowReferenceKeyStartupValidator.class);

    private final ImportSourceRowReference rowReference;
    private final ObjectProvider<FeatureSwitches> featureSwitches;

    public ImportRowReferenceKeyStartupValidator(ImportSourceRowReference rowReference,
                                                 ObjectProvider<FeatureSwitches> featureSwitches) {
        this.rowReference = rowReference;
        this.featureSwitches = featureSwitches;
    }

    @PostConstruct
    void validate() {
        FeatureSwitches switches = featureSwitches == null ? null : featureSwitches.getIfAvailable();
        boolean enabled = switches != null && switches.isEnabled(Feature.NF06);
        ImportSourceRowReference.KeyState state = rowReference.state();
        if (state == ImportSourceRowReference.KeyState.AVAILABLE) {
            log.info("[取込] NF-06行参照鍵を確認しました: nf06Enabled={}", enabled);
            return;
        }
        if (!enabled) {
            if (state != ImportSourceRowReference.KeyState.MISSING) {
                log.warn("[取込] NF-06行参照鍵が使えませんが、NF-06がOFFのため起動を継続します: state={}", state);
            }
            return;
        }
        throw new IllegalStateException("本番のNF-06行参照鍵（" + ImportSourceRowReference.PROPERTY
                + "、IMPORT_ROW_REFERENCE_HMAC_KEY）が使えないため起動できません: state=" + state);
    }
}
