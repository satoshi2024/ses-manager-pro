package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.EnablementState;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NF05 の実行時の判定（feature-enablement-console design §9.3。FE-D03）。実行時の全ての NF05 の読取り（filter・監査の境界・backup の
 * 静止・producer・worker・barrier）はここを通す。開閉は「起動時の値 AND 実効値」で、開ける変更は次の起動で反映し、閉じる変更は即時に
 * 反映する（D-2）。
 *
 * <p>閉鎖の2種類（review P2-N3・micro review P3-M2）。{@link #outboundClosure()} が返す:</p>
 * <ul>
 *   <li>{@link Closure#TRANSIENT 一時の閉鎖}: 最後に読めた snapshot の開放状態が {@code OPEN} のままで、総スイッチが開で、自動閉鎖の
 *       書込み待ちでない（= producer の判定式）のに実効値が閉じている。原因は、{@code unverifiable_since}・この instance の memory の
 *       評価できない状態（overlay {@code EVALUATION_UNVERIFIABLE}）・commit の後の読直しの失敗（overlay {@code REFRESH_PENDING}）・
 *       snapshot の古さ・期限が来たが {@code AUTO_CLOSED} が未書込み（{@code openUntil} の経過）・要る署名の欠け（{@code openUntil=MIN}。
 *       次の書込みの transaction の先行の閉鎖で {@code AUTO_CLOSED} になる）・実行時条件の不成立。NF05 に親は無い（親の閉鎖は該当しない）。
 *       producer は outbox への書込みを続け、worker は claim を止め、処理中の配信を PENDING へ戻す。解ければ同じ process で再開する。
 *       起動の直後で開放状態が {@code OPEN_PENDING_RESTART} の間（起動の後の反映の前。{@link #markBootRecorded}）も一時の閉鎖に数える
 *       （producer は {@code OPEN} だけを見るため積まない）。snapshot を一度も読めていない間も一時の閉鎖（同じく積まない）。</li>
 *   <li>{@link Closure#DEFINITIVE 確定の閉鎖}: 起動時の値が閉、総スイッチが閉、自動閉鎖の書込み待ち（{@code AUTO_CLOSE_PENDING}。
 *       条件の不成立が確かめられているため。review P3-N1）、開放状態が {@code OPEN} 以外（{@code AUTO_CLOSED}・手動の閉鎖。起動の後の反映の
 *       後の {@code OPEN_PENDING_RESTART} を含む）。一度確定の閉鎖を見た方向は、この process の間は閉じたまま（同じ process では開き
 *       直さない。開き直しは {@code OPEN_PENDING_RESTART} → 再起動 → 隔離。他の instance が再起動で {@code OPEN} を書いてもこの instance
 *       は送らない）。現在の kill switch と同じ扱い（producer は積まず、worker は送信せず処理中は DLQ {@code KILL_SWITCH}）で、DB の
 *       状態が確定の閉鎖の間は未送信の配信を barrier が開き直しの起動の隔離と同じ DLQ {@code STARTUP_REVALIDATION_REQUIRED} にする
 *       （保存期間で purge し、開き直した後は bulk replay で戻せる。review rb-fe-d P2-2・P2-N2）。</li>
 * </ul>
 * <p>property の源（dev・test。目録の DB 管理の印が無い間を含む）では、閉は全て確定の閉鎖（従来の kill switch）。Spring 管理外で
 * {@code new} した部品は {@link #inboundOpen(IntegrationHubRuntimeSwitch, IntegrationHubExternalApiProperties)} 等の static で
 * properties の値をそのまま使う（従来どおり）。</p>
 */
@Slf4j
@Component
public class IntegrationHubRuntimeSwitch {

    /** outbound の閉鎖の種類。 */
    public enum Closure { NONE, TRANSIENT, DEFINITIVE }

    private final IntegrationHubExternalApiProperties properties;
    private final ObjectProvider<FeatureSwitches> switches;
    private final ObjectProvider<FeatureEnablementSnapshotHolder> holder;
    private final ObjectProvider<FeatureEnablementOverlay> overlay;
    private final ObjectProvider<FeatureEnablementProperties> featureProperties;
    /** 確定の閉鎖を見た方向（この process の間は閉じたまま）。 */
    private final Set<Feature> latched = ConcurrentHashMap.newKeySet();
    /** 起動の後の反映（{@code IntegrationHubBootDecisionRecorder}）が終わったか。 */
    private volatile boolean bootRecorded;

    public IntegrationHubRuntimeSwitch(IntegrationHubExternalApiProperties properties,
                                       ObjectProvider<FeatureSwitches> switches,
                                       ObjectProvider<FeatureEnablementSnapshotHolder> holder,
                                       ObjectProvider<FeatureEnablementOverlay> overlay,
                                       ObjectProvider<FeatureEnablementProperties> featureProperties) {
        this.properties = properties;
        this.switches = switches;
        this.holder = holder;
        this.overlay = overlay;
        this.featureProperties = featureProperties;
    }

    /** 起動時の値（boot resolver が書いた property を bind した値。property の源では設定値）。 */
    public boolean bootOpen(Feature feature) {
        return feature == Feature.NF05_INBOUND ? inboundBootOpen(properties) : outboundBootOpen(properties);
    }

    /** inbound が開いているか（閉じていれば 404 {@code DISABLED_DENY}。どちらの閉鎖でも同じ）。 */
    public boolean inboundOpen() {
        return closure(Feature.NF05_INBOUND) == Closure.NONE;
    }

    /** outbound が開いているか。 */
    public boolean outboundOpen() {
        return outboundClosure() == Closure.NONE;
    }

    /** outbound の閉鎖の種類。 */
    public Closure outboundClosure() {
        return closure(Feature.NF05_OUTBOUND);
    }

    /**
     * producer が outbox へ書くか（起動時の値 ∧ 最後に読めた snapshot の開放状態が {@code OPEN} ∧ 総スイッチ ∧ 書込み待ちでない。
     * 一時の閉鎖の間も積む。design §9.3）。
     */
    public boolean outboundProducerOpen() {
        Closure closure = outboundClosure();
        if (closure == Closure.NONE) {
            return true;
        }
        if (closure == Closure.DEFINITIVE) {
            return false;
        }
        FeatureEnablementSnapshotHolder snapshots = holder.getIfAvailable();
        if (snapshots == null) {
            return false;
        }
        FeatureEnablementSnapshot snapshot = snapshots.get();
        return snapshot.generation() >= 0 && snapshot.unit(Feature.NF05_OUTBOUND).state() == EnablementState.OPEN;
    }

    /** 起動の後の反映が終わった（以後の {@code OPEN_PENDING_RESTART} は確定の閉鎖）。 */
    public void markBootRecorded() {
        bootRecorded = true;
    }

    /** 閉鎖の種類を決める。 */
    public Closure closure(Feature feature) {
        if (feature != Feature.NF05_INBOUND && feature != Feature.NF05_OUTBOUND) {
            throw new IllegalArgumentException("NF05 の開放単位ではありません");
        }
        if (!bootOpen(feature)) {
            return Closure.DEFINITIVE;
        }
        FeatureSwitches current = switches.getIfAvailable();
        if (current == null) {
            return Closure.NONE;
        }
        if (!databaseSource()) {
            return current.isEnabled(feature) ? Closure.NONE : Closure.DEFINITIVE;
        }
        if (latched.contains(feature)) {
            return Closure.DEFINITIVE;
        }
        if (current.isEnabled(feature)) {
            return Closure.NONE;
        }
        FeatureEnablementSnapshotHolder snapshots = holder.getIfAvailable();
        FeatureEnablementOverlay overlays = overlay.getIfAvailable();
        FeatureEnablementProperties settings = featureProperties.getIfAvailable();
        FeatureEnablementSnapshot snapshot = snapshots == null ? null : snapshots.get();
        boolean master = (settings == null || settings.isAllowed()) && (snapshot == null || snapshot.masterAllowed());
        if (!master || (overlays != null && overlays.has(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING))) {
            return latch(feature, !master ? "MASTER_SWITCH_CLOSED" : "AUTO_CLOSE_PENDING");
        }
        if (snapshot == null || snapshot.generation() < 0) {
            return Closure.TRANSIENT;
        }
        EnablementState state = snapshot.unit(feature).state();
        if (state == EnablementState.OPEN) {
            return Closure.TRANSIENT;
        }
        if (state == EnablementState.OPEN_PENDING_RESTART && !bootRecorded) {
            return Closure.TRANSIENT;
        }
        return latch(feature, "state." + state.name());
    }

    private Closure latch(Feature feature, String reason) {
        if (latched.add(feature)) {
            log.warn("[機能の開放] NF05 を確定の閉鎖として閉じます（この process では開き直さない）: "
                    + "errorCode=NF05_DEFINITIVE_CLOSURE feature={} reason={}", feature.name(), reason);
        }
        return Closure.DEFINITIVE;
    }

    private boolean databaseSource() {
        FeatureEnablementProperties settings = featureProperties.getIfAvailable();
        return settings != null && settings.databaseSource();
    }

    /**
     * DB の開放状態が確定の閉鎖か（未送信の配信を終わった状態にしてよいか。review rb-fe-d P2-2）。DB の源で、snapshot を読めていて、
     * 総スイッチが閉か、開放状態が {@code OPEN}・{@code OPEN_PENDING_RESTART} 以外の時だけ true。自動閉鎖の書込み待ち
     * （DB はまだ {@code OPEN}）・{@code OPEN}（他の instance が送っているかもしれない）・property の源は false。
     */
    public boolean definitivelyClosedInDatabase(Feature feature) {
        if (!databaseSource()) {
            return false;
        }
        FeatureEnablementSnapshotHolder snapshots = holder.getIfAvailable();
        FeatureEnablementProperties settings = featureProperties.getIfAvailable();
        FeatureEnablementSnapshot snapshot = snapshots == null ? null : snapshots.get();
        if (snapshot == null || snapshot.generation() < 0) {
            return false;
        }
        if ((settings != null && !settings.isAllowed()) || !snapshot.masterAllowed()) {
            return true;
        }
        return !snapshot.unit(feature).state().openLike();
    }

    // ---------------------------------------------------------------- 1つの要求の中の判定（review rb-fe-d P2-1）

    /** 要求の中で一度だけ決めた inbound の開閉を置く request attribute。 */
    public static final String REQUEST_ATTRIBUTE = IntegrationHubRuntimeSwitch.class.getName() + ".inboundOpen";

    /**
     * 1つの要求の inbound の開閉（最初に呼んだ filter が決め、request attribute に置く。以後の filter と監査の境界は同じ値を読む）。
     * 要求の途中で実効値が変わっても、backup の静止の permit・404・認証・監査の判定が食い違わない（開→閉で実行済みの要求の監査が
     * 落ちる、閉→開で静止中の外部の書込みが permit を取らない、を防ぐ）。properties が無ければ決めずに閉を返す。
     */
    public static boolean inboundOpen(jakarta.servlet.http.HttpServletRequest request,
                                      IntegrationHubRuntimeSwitch runtimeSwitch,
                                      IntegrationHubExternalApiProperties properties) {
        if (request != null && request.getAttribute(REQUEST_ATTRIBUTE) instanceof Boolean decided) {
            return decided;
        }
        if (properties == null) {
            return false;
        }
        boolean open = inboundOpen(runtimeSwitch, properties);
        if (request != null) {
            request.setAttribute(REQUEST_ATTRIBUTE, open);
        }
        return open;
    }

    /**
     * 1つの要求の inbound が閉で、起動時の値も閉か（LB が遮断している。review rb-fe-d P2-N1）。この時だけ、外部の要求は DB へ書かない
     * （監査の境界は永続化せず、backup の静止の filter は読取りとして扱い静止中も平時の 404）。実行時の閉鎖（起動時の値は開・LB は開）の
     * 要求は監査の行を書くため、静止の filter は外部の書込みとして permit を求め、静止中は 503 {@code BACKUP_QUIESCED} にする（開放した
     * 公開 API の静止中の扱いと同じ。deploy-runbook §1.7 の NF05 の (g)）。2つの filter が同じ判定式を使う。
     */
    public static boolean inboundClosedSinceBoot(jakarta.servlet.http.HttpServletRequest request,
                                                 IntegrationHubRuntimeSwitch runtimeSwitch,
                                                 IntegrationHubExternalApiProperties properties) {
        return !inboundOpen(request, runtimeSwitch, properties) && !inboundBootOpen(properties);
    }

    // ---------------------------------------------------------------- Spring 管理外の部品（従来の判定）

    /** inbound が開いているか（switch が無ければ properties の値。従来どおり）。 */
    public static boolean inboundOpen(IntegrationHubRuntimeSwitch runtimeSwitch,
                                      IntegrationHubExternalApiProperties properties) {
        return runtimeSwitch != null ? runtimeSwitch.inboundOpen() : inboundBootOpen(properties);
    }

    /** outbound の閉鎖の種類（switch が無ければ properties の値で、閉は確定の閉鎖。従来の kill switch）。 */
    public static Closure outboundClosure(IntegrationHubRuntimeSwitch runtimeSwitch,
                                          IntegrationHubExternalApiProperties properties) {
        if (runtimeSwitch != null) {
            return runtimeSwitch.outboundClosure();
        }
        return outboundBootOpen(properties) ? Closure.NONE : Closure.DEFINITIVE;
    }

    /** producer が outbox へ書くか（switch が無ければ properties の値）。 */
    public static boolean outboundProducerOpen(IntegrationHubRuntimeSwitch runtimeSwitch,
                                               IntegrationHubExternalApiProperties properties) {
        return runtimeSwitch != null ? runtimeSwitch.outboundProducerOpen() : outboundBootOpen(properties);
    }

    /** inbound の起動時の値（{@code integration.hub.public-api.enabled}）。 */
    public static boolean inboundBootOpen(IntegrationHubExternalApiProperties properties) {
        return properties != null && properties.getPublicApi() != null
                && Boolean.TRUE.equals(properties.getPublicApi().getEnabled());
    }

    /** outbound の起動時の値（{@code integration.hub.external-transport.enabled}）。 */
    public static boolean outboundBootOpen(IntegrationHubExternalApiProperties properties) {
        return properties != null && properties.getExternalTransport() != null
                && Boolean.TRUE.equals(properties.getExternalTransport().getEnabled());
    }
}
