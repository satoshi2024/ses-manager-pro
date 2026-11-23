package com.ses.importcenter.metadata;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本番起動時のNF-06行参照鍵（{@value ImportSourceRowReference#PROPERTY}）の確認（W3-12。W3-05 review P2-1）。起動は止めない
 * （feature-enablement-console design §8.3、requirements §7 の「機能を閉じる」。FE-B02）。
 *
 * <p>以前は NF-06 がONで鍵が未設定・形式不正・公開済みの鍵なら起動を失敗させていた。機能の開放では、同じ判定
 * （{@link ImportSourceRowReference#state()}）を profile に依らない評価器 {@code ImportCenterEnablementConditions} が使い、鍵が
 * 使えなければ NF-06 を開けない・開いていれば起動時・定期の評価で自動閉鎖する（閉じている間は preview・error export に到達しない）。
 * 鍵は起動時に読むため、差替えは配備の設定の変更と再起動で行う。messageには鍵の状態（固定値）だけを出し、鍵の値は出さない。</p>
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
                log.warn("[取込] NF-06行参照鍵が使えません（NF-06は閉じています）: state={}", state);
            }
            return;
        }
        log.warn("[取込] NF-06行参照鍵（" + ImportSourceRowReference.PROPERTY + "、IMPORT_ROW_REFERENCE_HMAC_KEY）が使えないため、"
                + "起動時の評価でNF-06を閉じます（起動は止めません）: state={}", state);
    }
}
