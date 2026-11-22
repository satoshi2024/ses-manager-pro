package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.exception.BusinessException;
import com.ses.config.MyBatisPlusConfig;
import com.ses.config.feature.Feature;
import com.ses.entity.AuditLog;
import com.ses.entity.FeatureEnablementEvent;
import com.ses.entity.FeatureEnablementGeneration;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureEnablementState;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.state.EnablementState;
import com.ses.featureenablement.state.FeatureEnablementMetrics;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementSnapshotLoader;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureEnablementEventMapper;
import com.ses.mapper.FeatureEnablementGenerationMapper;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import com.ses.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 機能の開放の書込みの共通部品（申請・承認・閉鎖・自動閉鎖・署名の遷移が同じ規則で書く。design §5.1・§2.7・§11.4）。
 *
 * <ul>
 *   <li>lock の順: (1) 世代の行（最初の書込みで作る）、(2) 開放状態の行（親→子、同じ階層は key の昇順）、(3) 申請の行、(4) 署名の行
 *       （id の昇順）、(5) 設定の行。(1) で tenant の中の変更が直列になるため、本機能の表どうしでは deadlock しない。</li>
 *   <li>世代の行を lock した直後に同じ transaction の中で snapshot を読み直し（memory の snapshot を使わない。READ COMMITTED）、
 *       {@code openUntil} を過ぎた開いている開放単位を先に {@code AUTO_CLOSED(SIGN_OFF_EXPIRED)} にする（review P2-2 (b)）。</li>
 *   <li>遷移ごとに履歴（追記専用）・監査（{@code recordDomainEventRequired}。method {@code FEAT_EN}）・INFO log
 *       {@code FEATURE_ENABLEMENT_EVENT} を1件ずつ書く（氏名と自由記述を含めない）。監査の失敗は遷移ごと rollback する。</li>
 *   <li>何かを書いた transaction は世代を1つ上げ、commit の後に snapshot を読み直す。</li>
 * </ul>
 */
@Slf4j
@Component
public class FeatureEnablementWriteSupport {

    /** 監査の method（{@code t_audit_log.method} は VARCHAR(10)。{@code AuditLogMethodLengthGuardTest}）。 */
    public static final String AUDIT_METHOD = "FEAT_EN";
    public static final String AUDIT_REFERENCE_TYPE = "FEATURE_ENABLEMENT";

    private final FeatureEnablementGenerationMapper generationMapper;
    private final FeatureEnablementStateMapper stateMapper;
    private final FeatureEnablementRequestMapper requestMapper;
    private final FeatureEnablementEventMapper eventMapper;
    private final AuditLogService auditLogService;
    private final FeatureEnablementSnapshotLoader loader;
    private final FeatureEnablementSnapshotHolder holder;
    private final FeatureEnablementCatalog catalog;
    private final FeatureEnablementTenant tenant;
    private final FeatureEnablementNotifier notifier;
    private final ObjectProvider<FeatureEnablementClosureHook> closureHooks;
    private final ObjectProvider<FeatureEnablementMetrics> metrics;
    private final Clock clock;

    public FeatureEnablementWriteSupport(FeatureEnablementGenerationMapper generationMapper,
                                         FeatureEnablementStateMapper stateMapper,
                                         FeatureEnablementRequestMapper requestMapper,
                                         FeatureEnablementEventMapper eventMapper,
                                         AuditLogService auditLogService,
                                         FeatureEnablementSnapshotLoader loader,
                                         FeatureEnablementSnapshotHolder holder,
                                         FeatureEnablementCatalog catalog,
                                         FeatureEnablementTenant tenant,
                                         FeatureEnablementNotifier notifier,
                                         ObjectProvider<FeatureEnablementClosureHook> closureHooks,
                                         ObjectProvider<FeatureEnablementMetrics> metrics,
                                         Clock clock) {
        this.generationMapper = generationMapper;
        this.stateMapper = stateMapper;
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
        this.auditLogService = auditLogService;
        this.loader = loader;
        this.holder = holder;
        this.catalog = catalog;
        this.tenant = tenant;
        this.notifier = notifier;
        this.closureHooks = closureHooks;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 書込みの文脈

    /** 書込みの行為者（利用者か system）。 */
    public record Actor(Long userId, String username, boolean system, boolean startup) {

        public static Actor of(FeatureEnablementActor actor) {
            return new Actor(actor.userId(), actor.username(), false, false);
        }

        /** 定期の評価・日の始まりの処理。 */
        public static Actor scheduler() {
            return new Actor(null, "SYSTEM", true, false);
        }

        /** 起動時の評価。 */
        public static Actor startupSystem() {
            return new Actor(null, "SYSTEM", true, true);
        }
    }

    /** 1つの書込みの transaction の文脈（世代の lock を取った後）。 */
    public final class WriteContext {

        private final String tenantId;
        private final String correlationId;
        private final LocalDateTime now;
        private final LocalDate today;
        private final long baseGeneration;
        private final Actor actor;
        private final Set<Feature> changed = EnumSet.noneOf(Feature.class);
        private boolean dirty;
        private FeatureEnablementSnapshot snapshot;

        private WriteContext(String tenantId, long baseGeneration, Actor actor) {
            this.tenantId = tenantId;
            this.correlationId = UUID.randomUUID().toString();
            this.now = MyBatisPlusConfig.AutoFillMetaObjectHandler.rowTimestamp(clock);
            this.today = FeatureEvaluationContext.businessDate(clock);
            this.baseGeneration = baseGeneration;
            this.actor = actor;
        }

        public String tenantId() {
            return tenantId;
        }

        public LocalDateTime now() {
            return now;
        }

        public LocalDate today() {
            return today;
        }

        public Actor actor() {
            return actor;
        }

        public String correlationId() {
            return correlationId;
        }

        /** 遷移の後の世代（この transaction が上げる値）。 */
        public long nextGeneration() {
            return baseGeneration + 1;
        }

        /** lock の後に読み直した snapshot（遷移を書いた後に {@link #reread} で読み直す）。 */
        public FeatureEnablementSnapshot snapshot() {
            return snapshot;
        }

        /** 同じ transaction の中で snapshot を読み直す（書いた遷移を条件の評価に反映する）。 */
        public FeatureEnablementSnapshot reread() {
            snapshot = loader.load(tenantId);
            return snapshot;
        }

        /** 書いた（世代を上げ、commit の後に読み直す）。 */
        public void markChanged(Feature feature) {
            dirty = true;
            if (feature != null) {
                changed.add(feature);
            }
        }

        public Set<Feature> changed() {
            return Set.copyOf(changed);
        }
    }

    /**
     * 世代の行を lock して書込みの文脈を作る（transaction の中で呼ぶ）。続けて snapshot を読み直し、{@code openUntil} を過ぎた
     * 開いている開放単位を先に自動閉鎖する。
     */
    public WriteContext begin(Actor actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("機能の開放の書込みは transaction の中で行う");
        }
        String tenantId = tenant.tenantId();
        LocalDateTime now = MyBatisPlusConfig.AutoFillMetaObjectHandler.rowTimestamp(clock);
        generationMapper.insertIgnore(tenantId, now);
        FeatureEnablementGeneration generation = generationMapper.selectForUpdate(tenantId);
        if (generation == null) {
            throw new IllegalStateException("機能の開放の世代の行を lock できません");
        }
        WriteContext context = new WriteContext(tenantId, generation.getGeneration() == null ? 0L
                : generation.getGeneration(), actor);
        context.reread();
        if (!closeExpired(context).isEmpty()) {
            context.reread();
        }
        return context;
    }

    /** 何かを書いていれば世代を上げ、commit の後に snapshot を読み直す（transaction の最後に呼ぶ）。 */
    public void finish(WriteContext context) {
        if (!context.dirty) {
            return;
        }
        if (generationMapper.increment(context.tenantId, context.now) != 1) {
            throw new IllegalStateException("機能の開放の世代を上げられません");
        }
        holder.reloadAfterCommit(context.changed());
    }

    // ---------------------------------------------------------------- 開放状態

    /** 開放状態の行を lock の順（親→子、同じ階層は key の昇順）に X lock する（行の無い開放単位は飛ばす）。 */
    public void lockStates(WriteContext context, Set<Feature> features) {
        List<Feature> ordered = new ArrayList<>(features);
        ordered.sort(Comparator.comparingInt(FeatureEnablementWriteSupport::depth).thenComparing(Feature::name));
        for (Feature feature : ordered) {
            stateMapper.selectByKeyForUpdate(context.tenantId, feature.name());
        }
    }

    /** 開放状態の行を X lock して読む（無ければ null）。 */
    public FeatureEnablementState lockState(WriteContext context, Feature feature) {
        return stateMapper.selectByKeyForUpdate(context.tenantId, feature.name());
    }

    /**
     * 開いている開放単位（{@code OPEN}・{@code OPEN_PENDING_RESTART}）を閉じる。開いた子も {@code AUTO_CLOSED(PARENT_CLOSED)} にし
     * （J-18）、閉鎖の hook を呼ぶ。開いていなければ何もしない（false）。
     *
     * @param manualCloser 手動の閉鎖の管理者（自動閉鎖は null）
     */
    public boolean closeUnit(WriteContext context, Feature feature, String reasonCode, Long manualCloser) {
        FeatureEnablementState row = stateMapper.selectByKeyForUpdate(context.tenantId, feature.name());
        if (row == null || !EnablementState.of(row.getState()).openLike()) {
            return false;
        }
        String newState = manualCloser == null ? EnablementState.AUTO_CLOSED.name() : EnablementState.MANUALLY_CLOSED.name();
        UpdateWrapper<FeatureEnablementState> update = new UpdateWrapper<>();
        update.eq("id", row.getId()).eq("version", row.getVersion())
                .set("state", newState).set("closed_reason", reasonCode).set("closed_by", manualCloser)
                .set("closed_at", context.now).set("unverifiable_since", null).set("unverifiable_reason", null)
                .set("version", row.getVersion() + 1).set("updated_at", context.now);
        if (stateMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        boolean manual = manualCloser != null;
        event(context, "FEATURE", feature.name(), row.getId(), manual ? "CLOSED" : "AUTO_CLOSED", row.getState(),
                newState, reasonCode, null);
        audit(context, manual ? "FEATURE_CLOSED" : "FEATURE_AUTO_CLOSED", row.getId(),
                "{\"feature\":\"" + feature.name() + "\",\"state\":\"" + row.getState() + "\"}",
                "{\"feature\":\"" + feature.name() + "\",\"state\":\"" + newState + "\",\"reason\":\"" + reasonCode + "\"}");
        context.markChanged(feature);
        if (!manual) {
            log.warn("[機能の開放] 開放単位を自動閉鎖しました: errorCode=FEATURE_AUTO_CLOSED feature={} reason={}",
                    feature.name(), reasonCode);
            FeatureEnablementMetrics meter = metrics == null ? null : metrics.getIfAvailable();
            if (meter != null) {
                meter.autoClosed(feature, reasonCode);
            }
        }
        closureHooks.orderedStream().forEach(hook -> hook.onClosed(context.tenantId, feature, newState, reasonCode));
        for (Feature child : catalog.children(feature)) {
            closeUnit(context, child, "PARENT_CLOSED", null);
        }
        return true;
    }

    /** 読み直した状態で {@code openUntil} を過ぎた開いている開放単位を {@code AUTO_CLOSED(SIGN_OFF_EXPIRED)} にする。 */
    public List<Feature> closeExpired(WriteContext context) {
        Set<Feature> closed = new LinkedHashSet<>();
        FeatureEnablementSnapshot snapshot = context.snapshot;
        List<Feature> ordered = new ArrayList<>(List.of(Feature.values()));
        ordered.sort(Comparator.comparingInt(FeatureEnablementWriteSupport::depth).thenComparing(Feature::name));
        for (Feature feature : ordered) {
            FeatureEnablementSnapshot.UnitView unit = snapshot.unit(feature);
            if (unit.state().openLike() && !context.today.isBefore(unit.openUntil()) && !closed.contains(feature)) {
                if (closeUnit(context, feature, "SIGN_OFF_EXPIRED", null)) {
                    closed.add(feature);
                    closed.addAll(catalog.children(feature));
                }
            }
        }
        return List.copyOf(closed);
    }

    // ---------------------------------------------------------------- 申請

    /** 対象の期限を過ぎた未決の申請を {@code EXPIRED} にする（null は tenant の全ての対象）。終えた件数。 */
    public int expirePending(WriteContext context, String targetKey) {
        int expired = 0;
        for (FeatureEnablementRequest request : requestMapper.selectExpiredPending(context.tenantId, context.now)) {
            if (targetKey != null && !targetKey.equals(request.getTargetKey())) {
                continue;
            }
            if (endBySystem(context, request, "EXPIRED", "FEATURE_OPEN_EXPIRED", "REQUEST_EXPIRED")) {
                expired++;
            }
        }
        return expired;
    }

    /** 対象の未決の申請を {@code CANCELLED} にする（閉鎖・総スイッチ）。 */
    public int cancelPending(WriteContext context, String targetKey, String reasonCode) {
        FeatureEnablementRequest pending = requestMapper.selectPendingByTarget(context.tenantId, targetKey);
        if (pending == null) {
            return 0;
        }
        FeatureEnablementRequest locked = requestMapper.selectByIdForUpdate(pending.getId());
        return locked != null && "PENDING".equals(locked.getStatus())
                && endBySystem(context, locked, "CANCELLED", "FEATURE_REQUEST_CANCELLED", reasonCode) ? 1 : 0;
    }

    /** system が申請を終える（{@code decided_by} は NULL）。 */
    public boolean endBySystem(WriteContext context, FeatureEnablementRequest request, String status,
                               String auditCode, String reasonCode) {
        UpdateWrapper<FeatureEnablementRequest> update = new UpdateWrapper<>();
        update.eq("id", request.getId()).eq("status", "PENDING").eq("version", request.getVersion())
                .set("status", status).set("decided_at", context.now).set("decision_reason", reasonCode)
                .set("version", request.getVersion() + 1);
        if (requestMapper.update(null, update) != 1) {
            return false;
        }
        event(context, "REQUEST", request.getTargetKey(), request.getId(), status, "PENDING", status, reasonCode, null);
        audit(context, auditCode, request.getId(), "{\"request\":" + request.getId() + ",\"status\":\"PENDING\"}",
                "{\"request\":" + request.getId() + ",\"status\":\"" + status + "\",\"reason\":\"" + reasonCode + "\"}");
        context.markChanged(featureOf(request.getTargetKey()));
        return true;
    }

    // ---------------------------------------------------------------- 履歴・監査

    /** 履歴を1行追記し、同じ内容を INFO log {@code FEATURE_ENABLEMENT_EVENT} に出す（氏名と自由記述を含めない）。 */
    public void event(WriteContext context, String subjectType, String subjectKey, Long subjectId, String eventType,
                      String fromState, String toState, String reasonCode, String conditionSummary) {
        FeatureEnablementEvent event = new FeatureEnablementEvent();
        event.setTenantId(context.tenantId);
        event.setSubjectType(subjectType);
        event.setSubjectKey(subjectKey);
        event.setSubjectId(subjectId);
        event.setEventType(eventType);
        event.setFromState(fromState);
        event.setToState(toState);
        event.setReasonCode(truncate(reasonCode, 64));
        event.setConditionSummary(truncate(conditionSummary, 2000));
        event.setActorType(context.actor.system() ? "SYSTEM" : "USER");
        event.setActorUserId(context.actor.userId());
        event.setCorrelationId(context.correlationId);
        event.setGeneration(context.nextGeneration());
        event.setOccurredAt(context.now);
        if (eventMapper.insert(event) != 1) {
            throw new IllegalStateException("機能の開放の履歴を書けません");
        }
        context.dirty = true;
        log.info("FEATURE_ENABLEMENT_EVENT tenant={} subjectType={} subjectKey={} subjectId={} eventType={} from={} to={} "
                        + "reason={} generation={} actorType={} actorUserId={} correlationId={}", context.tenantId,
                subjectType, subjectKey, subjectId, eventType, fromState, toState, reasonCode, context.nextGeneration(),
                event.getActorType(), context.actor.userId(), context.correlationId);
    }

    /** 監査を同じ transaction で書く（{@code recordDomainEventRequired}。失敗すれば遷移ごと rollback する）。 */
    public void audit(WriteContext context, String applicationCode, Long referenceId, String beforeState,
                      String afterState) {
        AuditLog entry = new AuditLog();
        entry.setUsername(context.actor.system() ? "SYSTEM" : context.actor.username());
        entry.setMethod(AUDIT_METHOD);
        entry.setUri(context.actor.system() ? (context.actor.startup() ? "system:feature-enablement/startup"
                : "system:feature-enablement/scheduler") : "/api/feature-enablement");
        entry.setStatus(200);
        entry.setApplicationCode(applicationCode);
        entry.setSuccessFlag(true);
        entry.setReferenceType(AUDIT_REFERENCE_TYPE);
        entry.setReferenceId(referenceId);
        entry.setActorType(context.actor.system() ? ActorType.SYSTEM.name() : ActorType.HUMAN.name());
        entry.setConfirmationSource(context.actor.system() ? ConfirmationSource.SCHEDULER_POLL.name()
                : ConfirmationSource.MANUAL_API.name());
        entry.setHumanUserId(context.actor.system() ? null : context.actor.userId());
        entry.setBeforeState(beforeState);
        entry.setAfterState(afterState);
        entry.setCorrelationId(context.correlationId);
        entry.setCreatedAt(context.now);
        auditLogService.recordDomainEventRequired(entry);
    }

    /** 通知の部品。 */
    public FeatureEnablementNotifier notifier() {
        return notifier;
    }

    /** 目録。 */
    public FeatureEnablementCatalog catalog() {
        return catalog;
    }

    /** 時計。 */
    public Clock clock() {
        return clock;
    }

    /** 申請の対象の key から開放単位（開放単位でなければ null）。 */
    public static Feature featureOf(String targetKey) {
        if (targetKey == null) {
            return null;
        }
        for (Feature feature : Feature.values()) {
            if (feature.name().equals(targetKey)) {
                return feature;
            }
        }
        return null;
    }

    private static int depth(Feature feature) {
        int depth = 0;
        for (Feature current = feature; current.parent().isPresent(); current = current.parent().get()) {
            depth++;
        }
        return depth;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** 状態の値（試験の補助）。 */
    static String stateOf(FeatureEnablementState row) {
        return row == null ? EnablementState.CLOSED.name() : Objects.requireNonNullElse(row.getState(), "CLOSED");
    }
}
