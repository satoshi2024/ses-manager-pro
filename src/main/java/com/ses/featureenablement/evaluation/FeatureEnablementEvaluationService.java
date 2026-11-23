package com.ses.featureenablement.evaluation;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.UnitDefinition;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.condition.SignOffRequirements;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.service.FeatureEnablementNotifier;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 開いている開放単位の継続評価と自動閉鎖（feature-enablement-console design §7.1・§7.2・§7.4。FE-RQ-005）。起動時の評価と定期の
 * 評価が同じ規則で使う。DB の源の時だけ動く。
 *
 * <ul>
 *   <li>自動閉鎖の書込み待ち（overlay の {@code AUTO_CLOSE_PENDING}）があれば、条件を見ずに書込みを再試行し、毎周期 WARN
 *       {@code errorCode=FEATURE_AUTO_CLOSE_PENDING} を出す（memory にあり再起動で失われるため。review P3-N1）。</li>
 *   <li>継続の条件（{@code CONTINUOUS}）が成り立たなければ直ちに overlay で閉じてから自動閉鎖を書く。{@code OPEN_ONLY}・
 *       {@code OPEN_TRANSITION} は閉鎖の根拠にしない（日の始まりの警告だけ）。</li>
 *   <li>評価できなければ {@code unverifiable_since} を DB に書き（全ての instance が snapshot で同じに閉じる）、書けなければこの
 *       instance だけ overlay で閉じる。始まりから評価の間隔の3倍で {@code AUTO_CLOSED(EVALUATION_UNVERIFIABLE)}（J-10）。回復で
 *       消す（書込み待ちは外さない）。</li>
 *   <li>総スイッチが閉じていれば、開いている全ての開放単位を {@code AUTO_CLOSED(MASTER_SWITCH_CLOSED)} にし、未決の申請を取り消す。</li>
 *   <li>日の始まり: 失効の反映・申請の期限切れ・失効の接近の通知・開放の時だけの条件の期限の接近と喪失の警告・拡張点。</li>
 * </ul>
 */
@Slf4j
@Component
public class FeatureEnablementEvaluationService {

    public static final String MASTER_SWITCH_CLOSED = "MASTER_SWITCH_CLOSED";
    public static final String EVALUATION_UNVERIFIABLE = "EVALUATION_UNVERIFIABLE";
    /** 失効の接近の通知の日数（{@code m_system_config}。1〜366、既定 30）。 */
    public static final String EXPIRY_WARNING_DAYS_KEY = "feature-enablement.expiry-warning-days";
    public static final int DEFAULT_EXPIRY_WARNING_DAYS = 30;
    /** 評価できない状態から自動閉鎖までの評価の間隔の倍数（J-10）。 */
    static final int UNVERIFIABLE_INTERVALS = 3;

    private final FeatureEnablementProperties properties;
    private final FeatureEnablementSnapshotHolder holder;
    private final FeatureConditionRegistry registry;
    private final FeatureEnablementCatalog catalog;
    private final FeatureEnablementOverlay overlay;
    private final FeatureEnablementService service;
    private final FeatureEnablementEvaluationWriter writer;
    private final FeatureEnablementNotifier notifier;
    private final ObjectProvider<SystemConfigService> systemConfig;
    private final ObjectProvider<FeatureEnablementDayStartHook> dayStartHooks;
    private final FeatureEnablementTenant tenant;
    private final Clock clock;

    /** DB に書けなかった評価できない状態の始まり（この instance だけ）。 */
    private final Map<Feature, Instant> localUnverifiableSince = new ConcurrentHashMap<>();
    /** 副入口の監視（前回の成否と固有の条件。FE-C04）。手で組み立てる試験では固有の条件の無いものを作る。 */
    private volatile SubgateMonitor subgateMonitor;
    /** 最後の評価の結果（画面の一覧の表示。FE-A09）。手で組み立てる試験では無い。 */
    private volatile FeatureEnablementEvaluationResults results;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResults(FeatureEnablementEvaluationResults results) {
        this.results = results;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubgateMonitor(SubgateMonitor subgateMonitor) {
        this.subgateMonitor = subgateMonitor;
    }

    public FeatureEnablementEvaluationService(FeatureEnablementProperties properties, FeatureEnablementSnapshotHolder holder,
                                              FeatureConditionRegistry registry, FeatureEnablementCatalog catalog,
                                              FeatureEnablementOverlay overlay, FeatureEnablementService service,
                                              FeatureEnablementEvaluationWriter writer, FeatureEnablementNotifier notifier,
                                              ObjectProvider<SystemConfigService> systemConfig,
                                              ObjectProvider<FeatureEnablementDayStartHook> dayStartHooks,
                                              FeatureEnablementTenant tenant, Clock clock) {
        this.properties = properties;
        this.holder = holder;
        this.registry = registry;
        this.catalog = catalog;
        this.overlay = overlay;
        this.service = service;
        this.writer = writer;
        this.notifier = notifier;
        this.systemConfig = systemConfig;
        this.dayStartHooks = dayStartHooks;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * 開いている開放単位を評価する（起動時は {@code STARTUP}、定期は {@code PERIODIC}）。
     *
     * @return 開放単位 → 評価の後の状態の表示（行の無い閉じた開放単位を含めない）
     */
    public Map<Feature, String> evaluateOpenUnits(FeatureEvaluationContext.Purpose purpose, boolean startup) {
        Map<Feature, String> result = new EnumMap<>(Feature.class);
        if (properties == null || !properties.databaseSource()) {
            return result;
        }
        holder.reload();
        if (!properties.isAllowed()) {
            closeAllForMaster(startup, result);
            return result;
        }
        for (Feature feature : orderedUnits()) {
            FeatureEnablementSnapshot.UnitView unit = holder.get().unit(feature);
            boolean pending = overlay.has(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
            if (pending) {
                String reason = overlay.pendingAutoCloseReason(feature).orElse(EVALUATION_UNVERIFIABLE);
                log.warn("[機能の開放] 自動閉鎖の書込み待ちです（書けるまで閉じたまま）: errorCode=FEATURE_AUTO_CLOSE_PENDING "
                        + "feature={} reason={}", feature.name(), reason);
                writeAutoClose(feature, reason, startup);
            } else if (unit.state().openLike() && !(startup && unit.restartPending())) {
                evaluateOpen(feature, purpose, startup);
            }
            FeatureEnablementSnapshot.UnitView after = holder.get().unit(feature);
            if (after.version() >= 0 || overlay.contains(feature)) {
                result.put(feature, label(feature, after));
            }
        }
        trackSubgates();
        return result;
    }

    /** 日の始まりの処理（JST 0:00 の後の最初の実行。design §7.2）。 */
    public void evaluateAtBusinessDayStart() {
        if (properties == null || !properties.databaseSource()) {
            return;
        }
        evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        runSafely("署名の失効の反映", writer::markExpiredSignOffs);
        runSafely("申請の期限切れ", service::expirePendingRequests);
        holder.reload();
        FeatureEnablementSnapshot snapshot = holder.get();
        LocalDate today = FeatureEvaluationContext.businessDate(clock);
        int warningDays = warningDays();
        expiryWarnings(snapshot, today, warningDays);
        openOnlyWarnings(snapshot, today, warningDays);
        dayStartHooks.orderedStream().forEach(hook -> {
            try {
                hook.onBusinessDayStart(holder.get(), today);
            } catch (RuntimeException e) {
                log.warn("[機能の開放] 日の始まりの拡張点が失敗しました: hook={} exceptionClass={}",
                        hook.getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        });
    }

    // ---------------------------------------------------------------- 評価と自動閉鎖

    private void evaluateOpen(Feature feature, FeatureEvaluationContext.Purpose purpose, boolean startup) {
        FeatureEnablementSnapshot snapshot = holder.get();
        FeatureEvaluation evaluation = registry.evaluate(feature, new FeatureEvaluationContext(tenant.tenantId(),
                FeatureEvaluationContext.businessDate(clock), clock, snapshot, purpose, Set.of(), true));
        FeatureEnablementEvaluationResults store = results;
        if (store != null) {
            store.record(evaluation);
        }
        if (!evaluation.closingUnsatisfied().isEmpty()) {
            String reason = truncate(evaluation.firstClosingReason().orElse("CONDITION_UNSATISFIED"));
            // 直ちに実効値を閉じる（書けるまで外さない）
            overlay.markAutoClosePending(feature, reason);
            writeAutoClose(feature, reason, startup);
        } else if (!evaluation.closingUnverifiable().isEmpty()) {
            unverifiable(feature, snapshot.unit(feature), evaluation.closingUnverifiable().get(0).code(), startup);
        } else {
            recovered(feature, snapshot.unit(feature), startup);
        }
    }

    private void unverifiable(Feature feature, FeatureEnablementSnapshot.UnitView unit, String code, boolean startup) {
        Instant since = unit.unverifiableSince();
        if (since == null) {
            try {
                Instant local = localUnverifiableSince.get(feature);
                writer.markUnverifiable(feature, code, startup, local == null ? null
                        : java.time.LocalDateTime.ofInstant(local, java.time.ZoneId.systemDefault()));
                holder.reload();
                since = holder.get().unit(feature).unverifiableSince();
                localUnverifiableSince.remove(feature);
            } catch (RuntimeException e) {
                overlay.close(feature, FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
                since = localUnverifiableSince.computeIfAbsent(feature, key -> clock.instant());
                log.warn("[機能の開放] 評価できない状態を DB に書けないため、この instance だけ閉じます: feature={} code={} "
                        + "exceptionClass={}", feature.name(), code, e.getClass().getSimpleName());
            }
        }
        if (since != null && !clock.instant().isBefore(
                since.plus(properties.getEvaluationInterval().multipliedBy(UNVERIFIABLE_INTERVALS)))) {
            overlay.markAutoClosePending(feature, EVALUATION_UNVERIFIABLE);
            writeAutoClose(feature, EVALUATION_UNVERIFIABLE, startup);
        }
    }

    private void recovered(Feature feature, FeatureEnablementSnapshot.UnitView unit, boolean startup) {
        localUnverifiableSince.remove(feature);
        if (unit.unverifiableSince() != null) {
            try {
                writer.clearUnverifiable(feature, startup);
                holder.reload();
            } catch (RuntimeException e) {
                log.warn("[機能の開放] 評価できない状態の回復を DB に書けません（次の周期で再試行）: feature={} exceptionClass={}",
                        feature.name(), e.getClass().getSimpleName());
                return;
            }
        }
        // 書込み待ち（AUTO_CLOSE_PENDING）は外さない
        overlay.open(feature, FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
    }

    /** 自動閉鎖を書く。書ければ書込み待ちを外し、書けなければ残して次の周期で再試行する。 */
    boolean writeAutoClose(Feature feature, String reason, boolean startup) {
        try {
            service.autoClose(feature, reason, startup);
        } catch (RuntimeException e) {
            overlay.markAutoClosePending(feature, reason);
            log.warn("[機能の開放] 自動閉鎖を書けないため書込み待ちで閉じたままにします: errorCode=FEATURE_AUTO_CLOSE_PENDING "
                    + "feature={} reason={} exceptionClass={}", feature.name(), reason, e.getClass().getSimpleName());
            return false;
        }
        holder.reload();
        overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        overlay.open(feature, FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
        localUnverifiableSince.remove(feature);
        return true;
    }

    private void closeAllForMaster(boolean startup, Map<Feature, String> result) {
        log.error("[機能の開放] 緊急の総スイッチが閉じているため、開いている全ての開放単位を閉じます: "
                + "errorCode=FEATURE_ENABLEMENT_MASTER_CLOSED");
        List<String> closed = new ArrayList<>();
        for (Feature feature : orderedUnits()) {
            FeatureEnablementSnapshot.UnitView unit = holder.get().unit(feature);
            if (unit.state().openLike() || overlay.has(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING)) {
                overlay.markAutoClosePending(feature, MASTER_SWITCH_CLOSED);
                if (writeAutoClose(feature, MASTER_SWITCH_CLOSED, startup) && unit.state().openLike()) {
                    closed.add(feature.name());
                }
            }
            FeatureEnablementSnapshot.UnitView after = holder.get().unit(feature);
            if (after.version() >= 0 || overlay.contains(feature)) {
                result.put(feature, label(feature, after));
            }
        }
        runSafely("総スイッチによる未決の申請の取消し", () -> writer.cancelAllPending(MASTER_SWITCH_CLOSED, startup));
        if (!closed.isEmpty()) {
            runSafely("総スイッチによる閉鎖の監査", () -> writer.recordMasterClosed(closed, startup));
        }
    }

    // ---------------------------------------------------------------- 日の始まりの警告

    private void expiryWarnings(FeatureEnablementSnapshot snapshot, LocalDate today, int warningDays) {
        for (Feature feature : orderedUnits()) {
            if (!snapshot.unit(feature).state().openLike()) {
                continue;
            }
            for (String kind : SignOffRequirements.applicableKinds(catalog, feature, snapshot.settings())) {
                FeatureEnablementSnapshot.ConfirmedSignOff signOff = snapshot.signOffs().get(kind);
                if (signOff == null) {
                    continue;
                }
                long days = ChronoUnit.DAYS.between(today, signOff.expiresOn());
                if (days > 0 && days <= warningDays) {
                    log.warn("[機能の開放] 署名の失効が近づいています: errorCode=FEATURE_SIGN_OFF_EXPIRING feature={} kind={} "
                            + "expiresOn={}", feature.name(), kind, signOff.expiresOn());
                    notifier.notifyAdministrators("expiryWarning", new Object[]{feature.name(), feature.displayName(), kind,
                            signOff.expiresOn()}, "FEATURE_ENABLEMENT:EXPIRY_WARNING:" + feature.name() + ":" + kind + ":"
                            + today, List.of());
                }
            }
        }
    }

    private void openOnlyWarnings(FeatureEnablementSnapshot snapshot, LocalDate today, int warningDays) {
        for (Feature feature : orderedUnits()) {
            UnitDefinition unit = catalog.unit(feature);
            if (unit.openOnlyCodes().isEmpty() || !snapshot.unit(feature).state().openLike()) {
                continue;
            }
            FeatureEvaluation evaluation = registry.evaluate(feature, new FeatureEvaluationContext(tenant.tenantId(), today,
                    clock, snapshot, FeatureEvaluationContext.Purpose.PERIODIC, Set.of(), true));
            for (FeatureConditionCheck check : evaluation.checks()) {
                if (check.scope() != FeatureConditionCheck.Scope.OPEN_ONLY) {
                    continue;
                }
                String event = null;
                if (!check.passing()) {
                    event = "LOST";
                } else if (check.validUntil() != null && ChronoUnit.DAYS.between(today, check.validUntil()) <= warningDays) {
                    event = "EXPIRING";
                }
                if (event == null) {
                    continue;
                }
                String auditCode = "FEATURE_OPEN_CONDITION_" + event;
                log.warn("[機能の開放] 開放の時だけの条件が{}（開いたまま）: errorCode={} feature={} check={}",
                        "LOST".equals(event) ? "外れています" : "期限に近づいています", auditCode, feature.name(),
                        check.code());
                runSafely("開放の時だけの条件の警告の監査",
                        () -> writer.recordOpenConditionWarning(feature, check.code(), auditCode));
                notifier.notifyAdministrators("LOST".equals(event) ? "openConditionLost" : "openConditionExpiring",
                        new Object[]{feature.name(), feature.displayName(), check.code()},
                        "FEATURE_ENABLEMENT:OPEN_CONDITION_" + event + ":" + feature.name() + ":" + check.code() + ":"
                                + today, List.of());
            }
        }
    }

    // ---------------------------------------------------------------- 副入口

    /**
     * 副入口の成否の変化を履歴に残し通知する（最初の観測は記録だけ）。判定は {@link SubgateMonitor}（固有の条件を含む。FE-C04）。
     * 手で組み立てる試験では、固有の条件の無い monitor を使う。
     */
    private void trackSubgates() {
        SubgateMonitor monitor = subgateMonitor;
        if (monitor == null) {
            monitor = new SubgateMonitor(catalog, null);
            subgateMonitor = monitor;
        }
        try {
            monitor.track(holder.get(), FeatureEvaluationContext.businessDate(clock), writer, notifier);
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 副入口の評価に失敗しました（次の周期で再試行）: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- helper

    /** 親から子の順の開放単位（目録にあるものだけ）。 */
    private List<Feature> orderedUnits() {
        List<Feature> features = new ArrayList<>();
        for (UnitDefinition unit : catalog.units()) {
            features.add(unit.feature());
        }
        features.sort(Comparator.comparingInt(FeatureEnablementEvaluationService::depth).thenComparing(Feature::name));
        return features;
    }

    private String label(Feature feature, FeatureEnablementSnapshot.UnitView unit) {
        String state = unit.state().name();
        if (overlay.has(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING)) {
            return "AUTO_CLOSE_PENDING(" + overlay.pendingAutoCloseReason(feature).orElse("-") + ")";
        }
        if (unit.closedReason() != null && !unit.state().openLike()) {
            return state + "(" + unit.closedReason() + ")";
        }
        if (unit.unverifiableSince() != null || overlay.has(feature, FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE)) {
            return state + "(" + EVALUATION_UNVERIFIABLE + ")";
        }
        return state;
    }

    private int warningDays() {
        SystemConfigService config = systemConfig == null ? null : systemConfig.getIfAvailable();
        if (config == null) {
            return DEFAULT_EXPIRY_WARNING_DAYS;
        }
        try {
            int days = config.getInt(EXPIRY_WARNING_DAYS_KEY, DEFAULT_EXPIRY_WARNING_DAYS);
            return days < 1 || days > 366 ? DEFAULT_EXPIRY_WARNING_DAYS : days;
        } catch (RuntimeException e) {
            return DEFAULT_EXPIRY_WARNING_DAYS;
        }
    }

    private static void runSafely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("[機能の開放] {}に失敗しました（次の周期で再試行）: exceptionClass={}", what, e.getClass().getSimpleName());
        }
    }

    private static int depth(Feature feature) {
        int depth = 0;
        for (Feature current = feature; current.parent().isPresent(); current = current.parent().get()) {
            depth++;
        }
        return depth;
    }

    private static String truncate(String code) {
        return code == null || code.length() <= 64 ? code : code.substring(0, 64);
    }
}
