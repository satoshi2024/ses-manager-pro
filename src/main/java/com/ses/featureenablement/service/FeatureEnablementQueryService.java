package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.FeatureEnablementEvent;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOffBundle;
import com.ses.entity.FeatureSignOffBundleMember;
import com.ses.featureenablement.catalog.BundleKind;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.KeyRequirement;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.catalog.SignOffRequirement;
import com.ses.featureenablement.catalog.UnitDefinition;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.condition.SignOffRequirements;
import com.ses.featureenablement.evaluation.FeatureEnablementEvaluationResults;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureEnablementEventMapper;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureSignOffBundleMapper;
import com.ses.mapper.FeatureSignOffBundleMemberMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 画面「機能の開放」の読取り（feature-enablement-console design §15.1・FE-RQ-011）。DB の状態に加えて実効値と閉じている理由を返し、
 * 条件は最後の評価の結果（memory）を出す。「再評価」は1件だけを {@code Purpose.DISPLAY} で評価し、状態を遷移させない（1分に1回まで。
 * review P3-20）。応答に署名者の氏名・元の file 名・保存名・鍵の値・digest の全体を含めない（FE-AC-011-02）。
 */
@Slf4j
@Service
public class FeatureEnablementQueryService {

    /** 画面の再評価の間隔の下限（開放単位ごと）。 */
    public static final Duration DISPLAY_EVALUATION_INTERVAL = Duration.ofMinutes(1);
    /** 一覧の既定の件数と上限（{@link PageUtils#safePage}）。 */
    static final long DEFAULT_PAGE_SIZE = 20L;
    static final long MAX_PAGE_SIZE = 200L;

    private final FeatureEnablementCatalog catalog;
    private final FeatureEnablementSnapshotHolder holder;
    private final FeatureSwitches switches;
    private final FeatureEnablementStateSource stateSource;
    private final FeatureConditionRegistry registry;
    private final FeatureEnablementEvaluationResults results;
    private final FeatureEnablementRequestMapper requestMapper;
    private final FeatureEnablementEventMapper eventMapper;
    private final FeatureSignOffBundleMapper bundleMapper;
    private final FeatureSignOffBundleMemberMapper memberMapper;
    private final FeatureEnablementTenant tenant;
    private final Clock clock;
    /** 閉鎖の警告の件数（拡張点。FE-B01 の NF01 二者確認の DUAL template の件数）。 */
    private final ObjectProvider<FeatureEnablementCloseWarningSource> closeWarningSources;
    private final Map<Feature, Instant> lastDisplayEvaluation = new ConcurrentHashMap<>();

    public FeatureEnablementQueryService(FeatureEnablementCatalog catalog, FeatureEnablementSnapshotHolder holder,
                                         FeatureSwitches switches, FeatureEnablementStateSource stateSource,
                                         FeatureConditionRegistry registry, FeatureEnablementEvaluationResults results,
                                         FeatureEnablementRequestMapper requestMapper,
                                         FeatureEnablementEventMapper eventMapper, FeatureSignOffBundleMapper bundleMapper,
                                         FeatureSignOffBundleMemberMapper memberMapper, FeatureEnablementTenant tenant,
                                         Clock clock, ObjectProvider<FeatureEnablementCloseWarningSource> closeWarningSources) {
        this.closeWarningSources = closeWarningSources;
        this.catalog = catalog;
        this.holder = holder;
        this.switches = switches;
        this.stateSource = stateSource;
        this.registry = registry;
        this.results = results;
        this.requestMapper = requestMapper;
        this.eventMapper = eventMapper;
        this.bundleMapper = bundleMapper;
        this.memberMapper = memberMapper;
        this.tenant = tenant;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 応答の形（値・氏名を含めない）

    /** 開放単位の一覧の1行。 */
    public record UnitSummary(String key, String displayName, String parentKey, boolean databaseManaged,
                              boolean restartRequired, boolean privacyUnit, String state, String closedReason,
                              int version, boolean restartPending, LocalDate openUntil, boolean effectiveOpen,
                              List<String> closedReasons, PendingRequest pendingRequest, EvaluationView lastEvaluation,
                              Long signOffDaysRemaining) {
    }

    /** 未決の申請の要約。 */
    public record PendingRequest(long id, String type, Long requestedBy, boolean mine, LocalDateTime requestedAt,
                                 LocalDateTime expiresAt, int version) {
    }

    /** 評価の結果（code と成否だけ）。 */
    public record EvaluationView(Instant evaluatedAt, String purpose, int satisfied, int failing, List<CheckView> checks) {
    }

    /** 条件の1件。 */
    public record CheckView(String code, String category, String scope, String status, boolean temporal,
                            LocalDate validUntil, List<String> detailCodes) {
    }

    /** 開放単位の詳細（{@code closeWarnings} は閉鎖の確認の dialog に出す警告の件数。design §5.5）。 */
    public record UnitDetail(UnitSummary unit, List<RequiredSignOff> signOffs, List<KeyView> keys, List<String> children,
                             List<RequestView> requests, List<String> subgates,
                             List<FeatureEnablementCloseWarningSource.CloseWarning> closeWarnings) {
    }

    /** 開放単位が要する署名の種別と現在の確認済みの署名（氏名を含めない）。 */
    public record RequiredSignOff(String kindCode, String kindClass, List<String> signerRoles, String conditionSetting,
                                  boolean applicable, String bundleKind, Long signOffId, Integer version,
                                  LocalDate signedOn, LocalDate expiresOn, String status, Long daysRemaining) {
    }

    /** 条件になる鍵（名前と、最後の評価で分かった有無だけ。値は持たない）。 */
    public record KeyView(String name, Boolean present) {
    }

    /** 申請（一覧・詳細）。 */
    public record RequestView(long id, String type, String target, String proposedValue, String status, Long requestedBy,
                              boolean mine, LocalDateTime requestedAt, LocalDateTime expiresAt, Long decidedBy,
                              LocalDateTime decidedAt, int version, String conditionSummary, String reason,
                              String decisionReason) {
    }

    /** 履歴の1件。 */
    public record EventView(long id, String subjectType, String subjectKey, Long subjectId, String eventType,
                            String fromState, String toState, String reasonCode, String actorType, Long actorUserId,
                            Long generation, LocalDateTime occurredAt) {
    }

    /** 一覧の頁。 */
    public record PageView<T>(List<T> records, long total, long current, long size) {
    }

    /** 署名の組の一覧の1行（member は種別と署名 ID だけ）。 */
    public record BundleView(long id, String bundleKind, String status, int version, Long registeredBy,
                             LocalDateTime registeredAt, Long confirmedBy, LocalDateTime confirmedAt, String endReason,
                             Map<String, Long> members, List<String> carriedOverKinds) {
    }

    /** 目録（画面の選択肢）。 */
    public record CatalogView(List<UnitCatalog> units, List<KindCatalog> kinds, List<BundleCatalog> bundles,
                              List<String> manualCloseReasons, Long actorUserId) {
    }

    public record UnitCatalog(String key, String displayName, String parentKey, List<String> signOffKinds,
                              List<String> keys) {
    }

    public record KindCatalog(String code, String kindClass, int maxValidityDays, List<String> signerRoles,
                              List<String> structuredFields, String bundleKind, boolean privacy, boolean extendable,
                              List<String> requiredBy) {
    }

    public record BundleCatalog(String code, List<String> requiredKinds, List<String> optionalKinds) {
    }

    // ---------------------------------------------------------------- 読取り

    /** 開放単位の一覧（目録の全件。{@link Feature} の順）。 */
    public List<UnitSummary> units(FeatureEnablementActor actor) {
        requireActor(actor);
        FeatureEnablementSnapshot snapshot = holder.get();
        Map<Feature, FeatureEnablementRequest> pending = pendingByFeature();
        List<UnitSummary> list = new ArrayList<>();
        for (UnitDefinition unit : catalog.units()) {
            list.add(summary(unit, snapshot, pending.get(unit.feature()), actor));
        }
        return list;
    }

    /** 開放単位の詳細。 */
    public UnitDetail unit(String key, FeatureEnablementActor actor) {
        requireActor(actor);
        Feature feature = feature(key);
        UnitDefinition unit = catalog.unit(feature);
        FeatureEnablementSnapshot snapshot = holder.get();
        LocalDate today = FeatureEvaluationContext.businessDate(clock);
        Set<String> applicable = Set.copyOf(SignOffRequirements.applicableKinds(catalog, feature, snapshot.settings()));
        List<RequiredSignOff> signOffs = new ArrayList<>();
        for (SignOffRequirement requirement : unit.signOffs()) {
            SignOffKind kind = catalog.kind(requirement.kindCode()).orElse(null);
            FeatureEnablementSnapshot.ConfirmedSignOff confirmed = snapshot.signOffs().get(requirement.kindCode());
            boolean isApplicable = applicable.contains(requirement.kindCode());
            String status = !isApplicable ? "NOT_APPLICABLE" : confirmed == null ? "MISSING"
                    : confirmed.signedOn().isAfter(today) ? "NOT_YET_SIGNED"
                    : today.isBefore(confirmed.expiresOn()) ? "CONFIRMED" : "EXPIRED";
            signOffs.add(new RequiredSignOff(requirement.kindCode(), kind == null ? null : kind.kindClass().name(),
                    kind == null ? List.of() : kind.signerRoles(), requirement.whenSettingSet(), isApplicable,
                    kind == null ? null : kind.bundleKind(), confirmed == null ? null : confirmed.id(),
                    confirmed == null ? null : confirmed.version(), confirmed == null ? null : confirmed.signedOn(),
                    confirmed == null ? null : confirmed.expiresOn(), status,
                    confirmed == null ? null : ChronoUnit.DAYS.between(today, confirmed.expiresOn())));
        }
        FeatureEvaluation evaluation = results.latest(feature).orElse(null);
        List<KeyView> keys = new ArrayList<>();
        for (KeyRequirement requirement : unit.keys()) {
            keys.add(new KeyView(requirement.name(), keyPresence(evaluation, requirement.name())));
        }
        List<String> children = catalog.children(feature).stream().map(Feature::name).toList();
        List<FeatureEnablementRequest> recent = requestMapper.selectList(new LambdaQueryWrapper<FeatureEnablementRequest>()
                .eq(FeatureEnablementRequest::getTenantId, tenant.tenantId())
                .eq(FeatureEnablementRequest::getTargetKey, feature.name())
                .orderByDesc(FeatureEnablementRequest::getId).last("LIMIT 10"));
        FeatureEnablementRequest pendingRequest = recent.stream().filter(request -> "PENDING".equals(request.getStatus()))
                .findFirst().orElse(null);
        List<RequestView> requests = recent.stream().map(request -> requestView(request, actor)).toList();
        List<String> subgates = unit.subgates().stream().map(subgate -> subgate.code()).toList();
        return new UnitDetail(summary(unit, snapshot, pendingRequest, actor), signOffs, keys, children, requests,
                subgates, closeWarnings(feature));
    }

    /** 閉鎖の警告（拡張点の件数。読めない拡張点は飛ばす。閉鎖そのものは妨げない）。 */
    private List<FeatureEnablementCloseWarningSource.CloseWarning> closeWarnings(Feature feature) {
        if (closeWarningSources == null) {
            return List.of();
        }
        List<FeatureEnablementCloseWarningSource.CloseWarning> warnings = new ArrayList<>();
        closeWarningSources.orderedStream().filter(source -> source.features().contains(feature)).forEach(source -> {
            try {
                for (FeatureEnablementCloseWarningSource.CloseWarning warning : source.closeWarnings(feature)) {
                    if (warning != null && warning.count() > 0) {
                        warnings.add(warning);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[機能の開放] 閉鎖の警告の件数を読めません（警告を出さずに続けます）: feature={} source={} exceptionClass={}",
                        feature.name(), source.getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        });
        return List.copyOf(warnings);
    }

    /**
     * その場で1件を評価する（{@code Purpose.DISPLAY}。結果を返して最後の評価として記録するだけで、状態を遷移させない）。開放単位ごとに
     * {@link #DISPLAY_EVALUATION_INTERVAL} に1回まで（それより早ければ 429）。
     */
    public EvaluationView evaluate(String key, FeatureEnablementActor actor) {
        requireActor(actor);
        Feature feature = feature(key);
        Instant now = clock.instant();
        boolean[] allowed = {false};
        lastDisplayEvaluation.compute(feature, (unit, last) -> {
            if (last == null || !now.isBefore(last.plus(DISPLAY_EVALUATION_INTERVAL))) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        if (!allowed[0]) {
            throw BusinessException.of(429, "error.featureEnablement.evaluateTooSoon");
        }
        FeatureEvaluation evaluation = registry.evaluate(feature, new FeatureEvaluationContext(tenant.tenantId(),
                FeatureEvaluationContext.businessDate(clock), clock, holder.get(), FeatureEvaluationContext.Purpose.DISPLAY,
                Set.of(feature), true));
        results.record(evaluation);
        return evaluationView(evaluation);
    }

    /** 未決と最近の申請（新しい順）。 */
    public PageView<RequestView> requests(long current, long size, String status, FeatureEnablementActor actor) {
        requireActor(actor);
        Page<FeatureEnablementRequest> page = PageUtils.safePage(current, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        LambdaQueryWrapper<FeatureEnablementRequest> wrapper = new LambdaQueryWrapper<FeatureEnablementRequest>()
                .eq(FeatureEnablementRequest::getTenantId, tenant.tenantId())
                .eq(status != null && !status.isBlank(), FeatureEnablementRequest::getStatus,
                        status == null ? null : status.trim())
                .orderByDesc(FeatureEnablementRequest::getId);
        Page<FeatureEnablementRequest> result = requestMapper.selectPage(page, wrapper);
        return new PageView<>(result.getRecords().stream().map(request -> requestView(request, actor)).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 履歴（新しい順。subject の種類・key・期間で絞る）。 */
    public PageView<EventView> events(long current, long size, String subjectType, String subjectKey, LocalDate from,
                                      LocalDate to, FeatureEnablementActor actor) {
        requireActor(actor);
        Page<FeatureEnablementEvent> page = PageUtils.safePage(current, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        LambdaQueryWrapper<FeatureEnablementEvent> wrapper = new LambdaQueryWrapper<FeatureEnablementEvent>()
                .eq(FeatureEnablementEvent::getTenantId, tenant.tenantId())
                .eq(subjectType != null && !subjectType.isBlank(), FeatureEnablementEvent::getSubjectType,
                        subjectType == null ? null : subjectType.trim())
                .eq(subjectKey != null && !subjectKey.isBlank(), FeatureEnablementEvent::getSubjectKey,
                        subjectKey == null ? null : subjectKey.trim())
                .ge(from != null, FeatureEnablementEvent::getOccurredAt, from == null ? null : from.atStartOfDay())
                .lt(to != null, FeatureEnablementEvent::getOccurredAt, to == null ? null : to.plusDays(1).atStartOfDay())
                .orderByDesc(FeatureEnablementEvent::getId);
        Page<FeatureEnablementEvent> result = eventMapper.selectPage(page, wrapper);
        return new PageView<>(result.getRecords().stream().map(FeatureEnablementQueryService::eventView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 署名の組の一覧（新しい順。member は種別と署名 ID だけ）。 */
    public List<BundleView> bundles(FeatureEnablementActor actor) {
        requireActor(actor);
        List<BundleView> list = new ArrayList<>();
        for (FeatureSignOffBundle bundle : bundleMapper.selectList(new LambdaQueryWrapper<FeatureSignOffBundle>()
                .eq(FeatureSignOffBundle::getTenantId, tenant.tenantId()).orderByDesc(FeatureSignOffBundle::getId)
                .last("LIMIT 100"))) {
            Map<String, Long> members = new LinkedHashMap<>();
            List<String> carried = new ArrayList<>();
            for (FeatureSignOffBundleMember member : memberMapper.selectByBundle(bundle.getId())) {
                members.put(member.getKindCode(), member.getSignOffId());
                if (Integer.valueOf(1).equals(member.getCarriedOver())) {
                    carried.add(member.getKindCode());
                }
            }
            list.add(new BundleView(bundle.getId(), bundle.getBundleKind(), bundle.getStatus(),
                    bundle.getVersion() == null ? 0 : bundle.getVersion(), bundle.getRegisteredBy(),
                    bundle.getRegisteredAt(), bundle.getConfirmedBy(), bundle.getConfirmedAt(), bundle.getEndReason(),
                    members, carried));
        }
        return list;
    }

    /** 目録（種別・類・上限・役割・構造化された値の項目・組。画面の選択肢）。 */
    public CatalogView catalog(FeatureEnablementActor actor) {
        requireActor(actor);
        List<UnitCatalog> units = new ArrayList<>();
        for (UnitDefinition unit : catalog.units()) {
            units.add(new UnitCatalog(unit.feature().name(), unit.feature().displayName(),
                    unit.feature().parent().map(Feature::name).orElse(null),
                    unit.signOffs().stream().map(SignOffRequirement::kindCode).toList(),
                    unit.keys().stream().map(KeyRequirement::name).toList()));
        }
        List<KindCatalog> kinds = new ArrayList<>();
        for (SignOffKind kind : catalog.kinds()) {
            kinds.add(new KindCatalog(kind.code(), kind.kindClass().name(), kind.kindClass().maxValidityDays(),
                    kind.signerRoles(), kind.structuredFields(), kind.bundleKind(), kind.privacy(),
                    kind.kindClass().extendable(), catalog.unitsRequiring(kind.code()).stream().map(Feature::name).toList()));
        }
        List<BundleCatalog> bundles = new ArrayList<>();
        for (BundleKind bundle : catalog.bundleKinds()) {
            bundles.add(new BundleCatalog(bundle.code(), bundle.requiredMembers(), bundle.optionalMembers()));
        }
        return new CatalogView(units, kinds, bundles, FeatureEnablementCatalog.MANUAL_CLOSE_REASONS, actor.userId());
    }

    /** 書いた申請の応答の形（申請・延長の申請の応答）。 */
    public RequestView view(FeatureEnablementRequest request, FeatureEnablementActor actor) {
        requireActor(actor);
        return requestView(request, actor);
    }

    // ---------------------------------------------------------------- helper

    private UnitSummary summary(UnitDefinition unit, FeatureEnablementSnapshot snapshot, FeatureEnablementRequest pending,
                                FeatureEnablementActor actor) {
        Feature feature = unit.feature();
        boolean managed = stateSource.database() && catalog.databaseManaged(feature);
        FeatureEnablementSnapshot.UnitView view = snapshot.unit(feature);
        boolean effective = switches.isEnabled(feature);
        LocalDate openUntil = managed && !LocalDate.MIN.equals(view.openUntil()) && !LocalDate.MAX.equals(view.openUntil())
                ? view.openUntil() : null;
        Long daysRemaining = openUntil == null ? null
                : ChronoUnit.DAYS.between(FeatureEvaluationContext.businessDate(clock), openUntil);
        return new UnitSummary(feature.name(), feature.displayName(), feature.parent().map(Feature::name).orElse(null),
                managed, unit.restartRequired(), unit.privacyUnit(), view.state().name(), view.closedReason(),
                view.version(), view.restartPending(), openUntil, effective,
                effective ? List.of() : switches.closedReasons(feature),
                pending == null ? null : new PendingRequest(pending.getId(), pending.getRequestType(),
                        pending.getRequestedBy(), Objects.equals(pending.getRequestedBy(), actor.userId()),
                        pending.getRequestedAt(), pending.getExpiresAt(),
                        pending.getVersion() == null ? 0 : pending.getVersion()),
                results.latest(feature).map(FeatureEnablementQueryService::evaluationView).orElse(null), daysRemaining);
    }

    /** 開放の未決の申請（開放単位ごとに高々1件。延長・設定の申請は対象を開放単位にしない）。 */
    private Map<Feature, FeatureEnablementRequest> pendingByFeature() {
        Map<Feature, FeatureEnablementRequest> pending = new EnumMap<>(Feature.class);
        for (FeatureEnablementRequest request : requestMapper.selectList(new LambdaQueryWrapper<FeatureEnablementRequest>()
                .eq(FeatureEnablementRequest::getTenantId, tenant.tenantId())
                .eq(FeatureEnablementRequest::getStatus, "PENDING"))) {
            Feature feature = featureOrNull(request.getTargetKey());
            if (feature != null) {
                pending.putIfAbsent(feature, request);
            }
        }
        return pending;
    }

    private static Boolean keyPresence(FeatureEvaluation evaluation, String name) {
        if (evaluation == null) {
            return null;
        }
        String prefix = "secret." + name + ".";
        for (FeatureConditionCheck check : evaluation.checks()) {
            if (check.code().startsWith(prefix)) {
                return check.code().equals(prefix + "present") ? Boolean.TRUE
                        : check.status() == FeatureConditionCheck.Status.UNVERIFIABLE ? null : Boolean.FALSE;
            }
        }
        return null;
    }

    static EvaluationView evaluationView(FeatureEvaluation evaluation) {
        List<CheckView> checks = evaluation.checks().stream()
                .map(check -> new CheckView(check.code(), check.category().name(), check.scope().name(),
                        check.status().name(), check.temporal(), check.validUntil(), check.detailCodes()))
                .toList();
        int satisfied = (int) evaluation.checks().stream().filter(FeatureConditionCheck::passing).count();
        return new EvaluationView(evaluation.evaluatedAt(), evaluation.purpose().name(), satisfied,
                evaluation.checks().size() - satisfied, checks);
    }

    private static RequestView requestView(FeatureEnablementRequest request, FeatureEnablementActor actor) {
        return new RequestView(request.getId(), request.getRequestType(), request.getTargetKey(),
                request.getProposedValue(), request.getStatus(), request.getRequestedBy(),
                Objects.equals(request.getRequestedBy(), actor.userId()), request.getRequestedAt(), request.getExpiresAt(),
                request.getDecidedBy(), request.getDecidedAt(), request.getVersion() == null ? 0 : request.getVersion(),
                request.getConditionSummary(), request.getReason(), request.getDecisionReason());
    }

    private static EventView eventView(FeatureEnablementEvent event) {
        return new EventView(event.getId(), event.getSubjectType(), event.getSubjectKey(), event.getSubjectId(),
                event.getEventType(), event.getFromState(), event.getToState(), event.getReasonCode(),
                event.getActorType(), event.getActorUserId(), event.getGeneration(), event.getOccurredAt());
    }

    private Feature feature(String key) {
        Feature feature = featureOrNull(key);
        if (feature == null || catalog.units().stream().noneMatch(unit -> unit.feature() == feature)) {
            throw BusinessException.of(404, "error.featureEnablement.unknownUnit");
        }
        return feature;
    }

    private static Feature featureOrNull(String key) {
        if (key == null) {
            return null;
        }
        try {
            return Feature.valueOf(key.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void requireActor(FeatureEnablementActor actor) {
        if (actor == null) {
            throw BusinessException.of(403, FeatureEnablementActorPolicy.ACTOR_NOT_ALLOWED);
        }
    }
}
