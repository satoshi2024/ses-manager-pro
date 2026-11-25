package com.ses.privacy.production.retention.trust;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NF07 の trust（D-03 = AWS KMS）の起動時の検査（NF07-TRUST）。prod でだけ動く。
 *
 * <p>機能の開閉（flag・開放状態）を読まない（feature-enablement-console design §8.5 の 4。FE-C04）。</p>
 * <ul>
 *   <li>trust が未設定（{@link UnconfiguredPrivacyTrustSource}。第1期）: 違反ではない（登録・有効化と解除の入口が404のまま）。</li>
 *   <li>KMS の trust を設定した: 開閉に関係なく常に検査する。設定（{@code anchor-file} が絶対 path、{@code max-document-age} が5分〜31日、
 *       {@code reload-interval} が1秒〜5分。時間の値は解釈できなければ {@code *.invalid}）と文書（読める・1MiB 以下・{@link KmsTrustDocument}
 *       の規則・file の I/O が20秒の内に終わる（EFS に届かなければ {@code trust.kms.document.timeout}））の<b>構造的な不足</b>は、起動を
 *       止めずに {@link PrivacyTrustStructureState} に置き、固定の違反コードだけを ERROR（{@value #LOG_CODE_STRUCTURE}）で出す。副入口
 *       REL（hold 解除）・REG（policy の登録と有効化）はその間 404 のまま閉じる（保持・保全の開放単位と hold の効力は閉じない）。値・公開鍵・
 *       ARN は出さない。構造の不足を直すには配備の設定・文書を直して再起動する（基盤の設定。要件 §6.11）。</li>
 *   <li><b>時間要因</b>（文書が鮮度の上限を過ぎた・作成日時が未来、有効期間内で ACTIVE の anchor が無い）だけなら WARN
 *       （{@value #LOG_CODE_NOT_READY}）を出す。入口は閉じたまま（gate が anchor・失効状態を確認できない）で、文書を置き直せば
 *       再起動せずに開く（決定の失効日と同じ扱い）。</li>
 * </ul>
 * 実行中に文書が壊れた・消えた場合は止めず、anchor 0件・失効状態 UNKNOWN（fail-closed）として扱い WARN を出す
 * （{@link KmsPrivacyTrustSource#LOG_CODE_STATE}）。trust の anchor・失効状態は {@link PrivacyTrustSource} の契約の呼出し
 * （{@code policyAnchors()} 等）では読まず、adapter の検査用の入口だけを使う。
 */
@Component
@Profile("prod")
public class PrivacyTrustStartupValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PrivacyTrustStartupValidator.class);

    /** 時間要因で trust を使えないまま起動したことを示す固定コード（監視の log 検索用）。 */
    public static final String LOG_CODE_NOT_READY = "NF07_TRUST_NOT_READY_AT_STARTUP";

    /** 構造的な不足で副入口 REL・REG を閉じたまま起動したことを示す固定コード（監視の log 検索用）。 */
    public static final String LOG_CODE_STRUCTURE = "NF07_TRUST_STRUCTURE_INVALID";

    private final PrivacyTrustSource privacyTrustSource;
    private final PrivacyTrustStructureState structureState;

    public PrivacyTrustStartupValidator(PrivacyTrustSource privacyTrustSource, PrivacyTrustStructureState structureState) {
        this.privacyTrustSource = privacyTrustSource;
        this.structureState = structureState == null ? new PrivacyTrustStructureState() : structureState;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /**
     * 前提を検査し、構造的な不足は {@link PrivacyTrustStructureState} に置いて ERROR を出す（起動は止めない）。時間要因だけなら WARN を
     * 出して続ける。
     */
    public void validate() {
        if (!(privacyTrustSource instanceof KmsPrivacyTrustSource kms)) {
            structureState.record(List.of());
            log.info("[privacy-trust] NF07のtrustは未設定です（D-03の方式のtrust文書なし。anchor 0件・失効状態UNKNOWN）");
            return;
        }
        KmsPrivacyTrustSource.StartupReport report = kms.inspect();
        structureState.record(report.structural());
        if (!report.structural().isEmpty()) {
            log.error("{}: NF07のtrust（D-03: AWS KMS）の設定・文書に構造的な不足があるため、hold解除とpolicyの登録・有効化の"
                    + "入口を閉じたまま起動します（保持・保全とholdの効力は閉じません）: {}", LOG_CODE_STRUCTURE,
                    report.structural());
            return;
        }
        if (!report.fresh() || (report.usablePolicyAnchors() == 0 && report.usableHoldReleaseAnchors() == 0)) {
            log.warn("{}: NF07のtrust文書は読めましたが、鮮度の上限の外か有効なanchorが無いため、hold解除とpolicyの"
                            + "登録・有効化の入口は閉じたままです: fresh={} policyAnchors={} holdReleaseAnchors={}",
                    LOG_CODE_NOT_READY, report.fresh(), report.usablePolicyAnchors(), report.usableHoldReleaseAnchors());
            return;
        }
        log.info("[privacy-trust] NF07のtrust文書を確認しました: policyAnchors={} holdReleaseAnchors={}",
                report.usablePolicyAnchors(), report.usableHoldReleaseAnchors());
    }
}
