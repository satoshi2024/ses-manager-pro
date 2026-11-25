package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.config.integrationhub.IntegrationHubApprovals;
import com.ses.config.integrationhub.IntegrationHubEnablementDecision;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.service.security.LegalEntityReadinessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * NF05 の起動の後の反映（feature-enablement-console design §9.2。FE-D02）。web server より前で、起動時の評価
 * （{@code FeatureEnablementStartupEvaluator}、phase 0）より前の phase で、起動の前の読取りの結果（property source の
 * {@code app.feature-enablement.nf05-boot.*}）を DB に反映する。DB の源の時だけ動く。
 *
 * <ul>
 *   <li>{@code OPEN_PENDING_RESTART} かつ {@code OPENED} → {@code OPEN}（{@code restart_applied_at}・履歴 {@code RESTART_APPLIED}・
 *       監査）。</li>
 *   <li>{@code OPEN}・{@code OPEN_PENDING_RESTART} かつ {@code CLOSED:<code>} → {@code AUTO_CLOSED(<code>)}（outbound は閉鎖の hook が
 *       barrier を OFF に書き直す）。</li>
 *   <li>{@code READ_FAILED} → 状態は変えず、開いている行に履歴 {@code BOOT_READ_FAILED}（画面は「この起動では閉」。実行時は起動時の値で
 *       閉）。{@code UNVERIFIABLE:<code>}（評価できない条件だけ。review rb-fe-d P3-3）も同じく状態を変えず履歴
 *       {@code BOOT_UNVERIFIABLE}。</li>
 *   <li>法人の未解決（{@code LegalEntityReadinessStartupValidator} の NF05 の部分を移した。port を開く前。review P3-5）: 起動時の値で
 *       開いた方向があり、未解決の行があれば例外にせず開いている方向を {@code AUTO_CLOSED(LEGAL_ENTITY_UNRESOLVED)} にする。判定できない
 *       時は状態を変えず、この instance の評価できない状態で閉じる（定期の評価が判定し直す）。</li>
 *   <li>起動の後に分かった安全網（{@code validateBoundaries} が閉じた方向）は {@code AUTO_CLOSED(<違反の code>)}。</li>
 *   <li>{@code RESTART_APPLIED} を書けなければ自動閉鎖の書込み待ち（{@code NF05_RESTART_NOT_APPLIED}。review rb-fe-d P3-2）。</li>
 * </ul>
 * 読んだ後に別の instance が変えた行は上書きしない（版の比較）。書けなければ自動閉鎖の書込み待ち（sticky）で閉じ、定期の評価が再試行する
 * （起動は止めない）。最後に開放状態を読み直してから runtime switch に反映の完了を伝える（以後の {@code OPEN_PENDING_RESTART} は確定の
 * 閉鎖。review rb-fe-d P3-1）。
 */
@Slf4j
@Component
public class IntegrationHubBootDecisionRecorder implements SmartLifecycle {

    /** 起動時の評価（phase 0）より前、barrier の初期化（{@code Integer.MIN_VALUE}）より後。 */
    static final int PHASE = -100;
    public static final String LEGAL_ENTITY_UNRESOLVED = "LEGAL_ENTITY_UNRESOLVED";
    public static final String LEGAL_ENTITY_UNVERIFIABLE = "LEGAL_ENTITY_UNVERIFIABLE";

    private final Environment environment;
    private final FeatureEnablementProperties properties;
    private final IntegrationHubBootDecisionWriter writer;
    private final IntegrationHubRuntimeSwitch runtimeSwitch;
    private final IntegrationHubExternalApiProperties integrationHub;
    private final ObjectProvider<LegalEntityReadinessService> readiness;
    private final FeatureEnablementOverlay overlay;
    private final ObjectProvider<com.ses.featureenablement.state.FeatureEnablementSnapshotHolder> holder;
    private volatile boolean running;

    /** {@code RESTART_APPLIED} を書けなかった時の自動閉鎖の理由（review rb-fe-d P3-2）。 */
    public static final String RESTART_NOT_APPLIED = "NF05_RESTART_NOT_APPLIED";

    public IntegrationHubBootDecisionRecorder(Environment environment, FeatureEnablementProperties properties,
                                              IntegrationHubBootDecisionWriter writer,
                                              IntegrationHubRuntimeSwitch runtimeSwitch,
                                              IntegrationHubExternalApiProperties integrationHub,
                                              ObjectProvider<LegalEntityReadinessService> readiness,
                                              FeatureEnablementOverlay overlay,
                                              ObjectProvider<com.ses.featureenablement.state.FeatureEnablementSnapshotHolder> holder) {
        this.environment = environment;
        this.properties = properties;
        this.writer = writer;
        this.runtimeSwitch = runtimeSwitch;
        this.integrationHub = integrationHub;
        this.readiness = readiness;
        this.overlay = overlay;
        this.holder = holder;
    }

    @Override
    public void start() {
        running = true;
        try {
            if (properties != null && properties.databaseSource()) {
                record();
            }
        } finally {
            // 反映の後の状態を読み直してから完了を伝える（commit の後の読直しの失敗や、他の instance が先に反映して UNCHANGED の時に、
            // 古い OPEN_PENDING_RESTART の snapshot で確定の閉鎖に倒さない。review rb-fe-d P3-1）
            reloadQuietly();
            runtimeSwitch.markBootRecorded();
        }
    }

    private void reloadQuietly() {
        com.ses.featureenablement.state.FeatureEnablementSnapshotHolder snapshots =
                holder == null ? null : holder.getIfAvailable();
        if (snapshots == null || properties == null || !properties.databaseSource()) {
            return;
        }
        try {
            snapshots.reload();
        } catch (RuntimeException e) {
            log.warn("[機能の開放] NF05 の起動の後の反映の後に開放状態を読み直せません: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    /** 反映して要約を返す（試験からも呼ぶ）。 */
    public String record() {
        List<String> summary = new ArrayList<>();
        List<Feature> legalEntityClosed = closeForLegalEntity(summary);
        for (Feature feature : List.of(Feature.NF05_INBOUND, Feature.NF05_OUTBOUND)) {
            if (legalEntityClosed.contains(feature)) {
                continue;
            }
            summary.add(feature.name() + "=" + apply(feature));
        }
        String described = String.join(", ", summary);
        log.info("[機能の開放] NF05 の起動の後の反映: {}", described);
        return described;
    }

    private String apply(Feature feature) {
        String direction = feature == Feature.NF05_INBOUND ? "inbound" : "outbound";
        String result = environment.getProperty(IntegrationHubApprovals.BOOT_PREFIX + direction + ".result");
        String readState = environment.getProperty(IntegrationHubApprovals.BOOT_PREFIX + direction + ".state", "NONE");
        int readVersion = parseVersion(environment.getProperty(IntegrationHubApprovals.BOOT_PREFIX + direction + ".version"));
        if (result == null) {
            return "NOT_READ";
        }
        try {
            if (IntegrationHubEnablementDecision.RESULT_READ_FAILED.equals(result)) {
                writer.recordReadFailed(feature);
                return result;
            }
            if (IntegrationHubEnablementDecision.RESULT_OPENED.equals(result)) {
                if (!runtimeSwitch.bootOpen(feature)) {
                    // 起動の境界の検査の安全網が閉じた（boot resolver と同じ関数のため通常は起きない）
                    String code = integrationHub.bootClosureCode(feature == Feature.NF05_INBOUND);
                    return "OPENED->" + close(feature, readState, -1, code == null ? "nf05.boot.safetyNet" : code);
                }
                if ("OPEN_PENDING_RESTART".equals(readState)) {
                    return "OPENED(" + applyRestart(feature, readVersion) + ")";
                }
                return result;
            }
            boolean openLike = "OPEN".equals(readState) || "OPEN_PENDING_RESTART".equals(readState);
            if (result.startsWith(IntegrationHubEnablementDecision.RESULT_CLOSED_PREFIX) && openLike) {
                return result + "->" + close(feature, readState, readVersion,
                        result.substring(IntegrationHubEnablementDecision.RESULT_CLOSED_PREFIX.length()));
            }
            if (result.startsWith(IntegrationHubEnablementDecision.RESULT_UNVERIFIABLE_PREFIX) && openLike) {
                // 評価できない（一時の失敗）: 状態は変えず、この起動だけ閉じる（次の起動で判定し直す。review rb-fe-d P3-3）
                writer.recordBootUnverifiable(feature,
                        result.substring(IntegrationHubEnablementDecision.RESULT_UNVERIFIABLE_PREFIX.length()));
                return result;
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("[機能の開放] NF05 の起動の後の反映を書けません（この起動では閉じたまま）: errorCode=NF05_BOOT_RECORD_FAILED "
                    + "feature={} exceptionClass={}", feature.name(), e.getClass().getSimpleName());
            return "RECORD_FAILED";
        }
    }

    /**
     * {@code OPEN_PENDING_RESTART} を {@code OPEN} にする。書けなければ自動閉鎖の書込み待ち（{@value #RESTART_NOT_APPLIED}）にし、定期の
     * 評価が理由を付けて {@code AUTO_CLOSED} を書き、閉鎖の hook が barrier を OFF に書き直す（この起動で barrier を QUARANTINING・ON に
     * 進めたまま行が再起動待ちで残ると、次の起動が OFF の記録を満たせず再承認が要るため、理由の分かる閉鎖にする。review rb-fe-d P3-2）。
     */
    private String applyRestart(Feature feature, int readVersion) {
        try {
            return writer.applyRestart(feature, readVersion).name();
        } catch (RuntimeException e) {
            overlay.markAutoClosePending(feature, RESTART_NOT_APPLIED);
            log.warn("[機能の開放] NF05 の再起動の反映を書けないため、書込み待ちで閉じます: errorCode=NF05_BOOT_RECORD_FAILED "
                    + "feature={} reason={} exceptionClass={}", feature.name(), RESTART_NOT_APPLIED, e.getClass().getSimpleName());
            return "AUTO_CLOSE_PENDING(" + RESTART_NOT_APPLIED + ")";
        }
    }

    /** 法人の未解決（起動時の値で開いた方向がある時だけ判定する。未解決なら開いている方向を閉じ、判定できなければこの起動だけ閉じる）。 */
    private List<Feature> closeForLegalEntity(List<String> summary) {
        List<Feature> open = new ArrayList<>();
        for (Feature feature : List.of(Feature.NF05_INBOUND, Feature.NF05_OUTBOUND)) {
            if (runtimeSwitch.bootOpen(feature)) {
                open.add(feature);
            }
        }
        LegalEntityReadinessService service = readiness == null ? null : readiness.getIfAvailable();
        if (open.isEmpty() || service == null) {
            return List.of();
        }
        try {
            if (service.isReady()) {
                return List.of();
            }
        } catch (RuntimeException e) {
            // 判定できない（一時の失敗）: 状態は変えず、この instance で評価できない状態として閉じる。定期の評価が同じ判定をし直し、
            // 戻れば開き、続けば評価の間隔の3倍で自動閉鎖する（review rb-fe-d P3-3）
            log.warn("[機能の開放] 法人の未解決を判定できないため NF05 をこの起動では閉じます: errorCode={} directions={} "
                    + "exceptionClass={}", LEGAL_ENTITY_UNVERIFIABLE, open, e.getClass().getSimpleName());
            for (Feature feature : open) {
                overlay.close(feature, FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
                try {
                    writer.recordBootUnverifiable(feature, LEGAL_ENTITY_UNVERIFIABLE);
                } catch (RuntimeException ignored) {
                    // 履歴を書けなくても閉じたまま（memory の評価できない状態）
                }
            }
            return List.of();
        }
        log.warn("[機能の開放] 法人の未解決の行があるため NF05 を閉じます（起動は止めない）: errorCode={} directions={}",
                LEGAL_ENTITY_UNRESOLVED, open);
        for (Feature feature : open) {
            summary.add(feature.name() + "=" + close(feature, null, -1, LEGAL_ENTITY_UNRESOLVED));
        }
        return open;
    }

    private String close(Feature feature, String readState, int readVersion, String code) {
        try {
            return "AUTO_CLOSED(" + code + "):" + writer.closeAtBoot(feature, readState, readVersion, code).name();
        } catch (RuntimeException e) {
            overlay.markAutoClosePending(feature, code);
            log.warn("[機能の開放] 自動閉鎖を書けないため書込み待ちで閉じたままにします: errorCode=FEATURE_AUTO_CLOSE_PENDING "
                    + "feature={} reason={} exceptionClass={}", feature.name(), code, e.getClass().getSimpleName());
            return "AUTO_CLOSE_PENDING(" + code + ")";
        }
    }

    private static int parseVersion(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
