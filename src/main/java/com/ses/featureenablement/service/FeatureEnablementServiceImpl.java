package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureEnablementState;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.UnitDefinition;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionFingerprint;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.EnablementState;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 機能の開放の申請・承認・却下・取下げ・閉鎖・自動閉鎖（feature-enablement-console design §5）。
 *
 * <ul>
 *   <li>全て READ COMMITTED（条件の評価で他の表の最新の commit を読むため。{@code WriterIsolationPolicyTest}）。</li>
 *   <li>世代の行を最初に lock し、同じ transaction の中で読み直した状態で条件を評価・指紋を計算する（review P2-3）。読み直しの直後に
 *       失効した開いている開放単位を先に自動閉鎖する（review P2-2 (b)）。</li>
 *   <li>申請の時も全ての条件の成立を要し、同じ対象の期限切れの未決を先に EXPIRED にする（review P3-7）。承認は版と、読み直した
 *       状態の指紋を確かめ、違えば申請を STALE で終えて commit してから 409 を返す（結果の値で返す。review P3-8）。承認は申請を先に
 *       APPROVED にしてから開放状態の行を書く（複合 FK。review P3-N7）。NF05 は OPEN_PENDING_RESTART にする。</li>
 *   <li>却下は申請者以外で理由が必須、取下げは申請者だけ。閉鎖は管理者1名で理由の code が必須、子を AUTO_CLOSED(PARENT_CLOSED)、
 *       未決を CANCELLED にし、全ての管理者へ通知する。</li>
 *   <li>NF07 の行為者は privacy-admin（{@link FeatureEnablementActorPolicy#PRIVACY_ADMIN_RULE_ENABLED}。段階 C で有効）。</li>
 *   <li>申請の種類は拡張点（{@link FeatureEnablementRequestApprover}）。閉鎖の hook は {@link FeatureEnablementClosureHook}。</li>
 * </ul>
 */
@Service
public class FeatureEnablementServiceImpl implements FeatureEnablementService {

    public static final String REQUEST_TYPE_OPEN = "OPEN";
    /** 閉鎖の理由の code の形（{@code closed_reason} は VARCHAR(64)。自由記述は書かない）。 */
    static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    static final int REASON_TEXT_MAX = 500;

    private final FeatureEnablementWriteSupport write;
    private final FeatureEnablementActorPolicy actorPolicy;
    private final FeatureConditionRegistry registry;
    private final FeatureEnablementCatalog catalog;
    private final FeatureEnablementRequestMapper requestMapper;
    private final FeatureEnablementStateMapper stateMapper;
    private final FeatureEnablementProperties properties;
    private final ObjectProvider<FeatureEnablementRequestApprover> approvers;

    public FeatureEnablementServiceImpl(FeatureEnablementWriteSupport write, FeatureEnablementActorPolicy actorPolicy,
                                        FeatureConditionRegistry registry, FeatureEnablementCatalog catalog,
                                        FeatureEnablementRequestMapper requestMapper,
                                        FeatureEnablementStateMapper stateMapper,
                                        FeatureEnablementProperties properties,
                                        ObjectProvider<FeatureEnablementRequestApprover> approvers) {
        this.write = write;
        this.actorPolicy = actorPolicy;
        this.registry = registry;
        this.catalog = catalog;
        this.requestMapper = requestMapper;
        this.stateMapper = stateMapper;
        this.properties = properties;
        this.approvers = approvers;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureEnablementRequest requestOpen(Feature feature, String reason, FeatureEnablementActor actor) {
        requireFeature(feature);
        FeatureEnablementActor checked = actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
        UnitDefinition unit = catalog.unit(feature);
        actorPolicy.requirePrivacyAdminIfRequired(unit.privacyUnit(), checked);
        requireMasterAllowed();
        String reasonText = optionalText(reason);

        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        write.lockStates(context, lineage(feature));
        write.expirePending(context, feature.name());
        FeatureEnablementSnapshot snapshot = context.reread();
        if (!snapshot.unit(feature).state().requestable()) {
            throw BusinessException.of(409, "error.featureEnablement.stateNotRequestable");
        }
        FeatureEvaluation evaluation = registry.evaluate(feature,
                evaluationContext(context, snapshot, FeatureEvaluationContext.Purpose.REQUEST, EnumSet.of(feature), true));
        if (!evaluation.openingAllowed()) {
            throw BusinessException.of(409, "error.featureEnablement.conditionsUnsatisfied", codes(evaluation));
        }

        FeatureEnablementRequest request = new FeatureEnablementRequest();
        request.setTenantId(context.tenantId());
        request.setRequestType(REQUEST_TYPE_OPEN);
        request.setTargetKey(feature.name());
        request.setStatus("PENDING");
        request.setConditionFingerprint(FeatureConditionFingerprint.of(catalog, feature, REQUEST_TYPE_OPEN, null, snapshot));
        request.setConditionSummary(evaluation.summary());
        request.setReason(reasonText);
        request.setRequestedBy(checked.userId());
        request.setRequestedAt(context.now());
        request.setExpiresAt(context.now().plus(REQUEST_TTL));
        request.setVersion(0);
        try {
            requestMapper.insert(request);
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.of(409, "error.featureEnablement.pendingExists");
        }
        write.event(context, "REQUEST", feature.name(), request.getId(), "REQUESTED", null, "PENDING", null,
                evaluation.summary());
        write.audit(context, "FEATURE_OPEN_REQUESTED", request.getId(), null,
                "{\"feature\":\"" + feature.name() + "\",\"request\":" + request.getId() + ",\"status\":\"PENDING\"}");
        context.markChanged(feature);
        write.finish(context);
        write.notifier().notifyAdministrators("requested", args(feature),
                "FEATURE_ENABLEMENT:REQUESTED:" + feature.name() + ":" + request.getId(), List.of(checked.userId()));
        return request;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureEnablementApprovalResult approve(long requestId, int expectedVersion, String reason,
                                                   FeatureEnablementActor actor) {
        FeatureEnablementActor checked = actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
        requireMasterAllowed();
        String reasonText = optionalText(reason);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureEnablementRequest request = lockPendingRequest(context, requestId, expectedVersion);
        if (checked.userId().equals(request.getRequestedBy())) {
            throw BusinessException.of(403, "error.featureEnablement.selfDecision");
        }
        if (!request.getExpiresAt().isAfter(context.now())) {
            write.endBySystem(context, request, "EXPIRED", "FEATURE_OPEN_EXPIRED", "REQUEST_EXPIRED");
            write.finish(context);
            return new FeatureEnablementApprovalResult(FeatureEnablementApprovalResult.Outcome.EXPIRED, requestId,
                    List.of());
        }
        FeatureEnablementApprovalResult result;
        if (REQUEST_TYPE_OPEN.equals(request.getRequestType())) {
            result = approveOpen(context, request, checked, reasonText);
        } else {
            FeatureEnablementRequestApprover approver = approvers.orderedStream()
                    .filter(candidate -> candidate.requestType().equals(request.getRequestType()))
                    .findFirst()
                    .orElseThrow(() -> BusinessException.of(409, "error.featureEnablement.requestTypeUnsupported"));
            result = approver.approveLocked(context, request, checked, reasonText);
        }
        write.finish(context);
        return result;
    }

    private FeatureEnablementApprovalResult approveOpen(FeatureEnablementWriteSupport.WriteContext context,
                                                        FeatureEnablementRequest request,
                                                        FeatureEnablementActor approver, String reasonText) {
        Feature feature = FeatureEnablementWriteSupport.featureOf(request.getTargetKey());
        if (feature == null) {
            throw BusinessException.of(409, "error.featureEnablement.unknownUnit");
        }
        UnitDefinition unit = catalog.unit(feature);
        actorPolicy.requirePrivacyAdminIfRequired(unit.privacyUnit(), approver);
        FeatureEnablementSnapshot snapshot = context.snapshot();
        EnablementState before = snapshot.unit(feature).state();
        if (!before.requestable()) {
            throw BusinessException.of(409, "error.featureEnablement.stateNotRequestable");
        }
        FeatureEvaluation evaluation = registry.evaluate(feature,
                evaluationContext(context, snapshot, FeatureEvaluationContext.Purpose.APPROVAL, EnumSet.of(feature), true));
        if (!evaluation.openingAllowed()) {
            // 申請は未決のまま（失効の先行の閉鎖は commit する）
            return new FeatureEnablementApprovalResult(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED,
                    request.getId(), evaluation.blocking().stream().map(FeatureConditionCheck::code).toList());
        }
        String fingerprint = FeatureConditionFingerprint.of(catalog, feature, REQUEST_TYPE_OPEN, null, snapshot);
        if (!fingerprint.equals(request.getConditionFingerprint())) {
            write.endBySystem(context, request, "STALE", "FEATURE_OPEN_STALE", "FINGERPRINT_CHANGED");
            return new FeatureEnablementApprovalResult(FeatureEnablementApprovalResult.Outcome.STALE, request.getId(),
                    List.of());
        }

        // 申請を先に APPROVED にする（開放状態の行の複合 FK が申請の行の申請者・決定者を参照する。review P3-N7）
        markApproved(context, request, approver, reasonText);
        String newState = unit.restartRequired() ? EnablementState.OPEN_PENDING_RESTART.name()
                : EnablementState.OPEN.name();
        FeatureEnablementState row = write.lockState(context, feature);
        if (row == null) {
            FeatureEnablementState created = new FeatureEnablementState();
            created.setTenantId(context.tenantId());
            created.setFeatureKey(feature.name());
            created.setState(newState);
            created.setVersion(0);
            created.setOpenRequestId(request.getId());
            created.setOpenedBy(request.getRequestedBy());
            created.setApprovedBy(approver.userId());
            created.setOpenedAt(context.now());
            created.setUpdatedAt(context.now());
            stateMapper.insert(created);
            row = created;
        } else {
            UpdateWrapper<FeatureEnablementState> update = new UpdateWrapper<>();
            update.eq("id", row.getId()).eq("version", row.getVersion())
                    .set("state", newState).set("open_request_id", request.getId())
                    .set("opened_by", request.getRequestedBy()).set("approved_by", approver.userId())
                    .set("opened_at", context.now()).set("restart_applied_at", null).set("closed_reason", null)
                    .set("closed_by", null).set("closed_at", null).set("unverifiable_since", null)
                    .set("unverifiable_reason", null).set("version", row.getVersion() + 1)
                    .set("updated_at", context.now());
            if (stateMapper.update(null, update) != 1) {
                throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
            }
        }
        write.event(context, "FEATURE", feature.name(), request.getId(), "APPROVED", before.name(), newState, null,
                evaluation.summary());
        write.audit(context, "FEATURE_OPEN_APPROVED", request.getId(),
                "{\"feature\":\"" + feature.name() + "\",\"state\":\"" + before.name() + "\"}",
                "{\"feature\":\"" + feature.name() + "\",\"state\":\"" + newState + "\",\"request\":" + request.getId()
                        + "}");
        context.markChanged(feature);
        write.notifier().notifyAdministrators("approved", args(feature),
                "FEATURE_ENABLEMENT:APPROVED:" + feature.name() + ":" + request.getId(), List.of());
        return FeatureEnablementApprovalResult.approved(request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void reject(long requestId, int expectedVersion, String reason, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
        String reasonText = requiredText(reason);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureEnablementRequest request = lockPendingRequest(context, requestId, expectedVersion);
        if (checked.userId().equals(request.getRequestedBy())) {
            throw BusinessException.of(403, "error.featureEnablement.selfDecision");
        }
        requirePrivacyAdminForTarget(request, checked);
        decide(context, request, "REJECTED", checked, reasonText, "FEATURE_OPEN_REJECTED");
        write.finish(context);
        write.notifier().notifyUser(request.getRequestedBy(), "rejected", targetArgs(request),
                "FEATURE_ENABLEMENT:REJECTED:" + request.getTargetKey() + ":" + request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void withdraw(long requestId, int expectedVersion, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureEnablementRequest request = lockPendingRequest(context, requestId, expectedVersion);
        if (!checked.userId().equals(request.getRequestedBy())) {
            throw BusinessException.of(403, "error.featureEnablement.withdrawOnlyRequester");
        }
        decide(context, request, "WITHDRAWN", checked, null, "FEATURE_OPEN_WITHDRAWN");
        write.finish(context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void close(Feature feature, String reason, FeatureEnablementActor actor) {
        requireFeature(feature);
        FeatureEnablementActor checked = actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
        actorPolicy.requirePrivacyAdminIfRequired(catalog.unit(feature).privacyUnit(), checked);
        String reasonCode = requiredReasonCode(reason);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        Set<Feature> affected = EnumSet.of(feature);
        affected.addAll(catalog.children(feature));
        write.lockStates(context, affected);
        if (!write.closeUnit(context, feature, reasonCode, checked.userId())) {
            throw BusinessException.of(409, "error.featureEnablement.stateNotOpen");
        }
        for (Feature target : affected) {
            write.cancelPending(context, target.name(), "PARENT_OR_UNIT_CLOSED");
        }
        write.finish(context);
        write.notifier().notifyAdministrators("closed", args(feature),
                "FEATURE_ENABLEMENT:CLOSED:" + feature.name() + ":" + context.correlationId(), List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean autoClose(Feature feature, String reasonCode, boolean startup) {
        requireFeature(feature);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(startup
                ? FeatureEnablementWriteSupport.Actor.startupSystem() : FeatureEnablementWriteSupport.Actor.scheduler());
        Set<Feature> affected = EnumSet.of(feature);
        affected.addAll(catalog.children(feature));
        write.lockStates(context, affected);
        boolean closed = write.closeUnit(context, feature, reasonCode, null);
        write.finish(context);
        if (closed) {
            write.notifier().notifyAdministrators("autoClosed", new Object[]{feature.name(), feature.displayName(),
                    reasonCode}, "FEATURE_ENABLEMENT:AUTO_CLOSED:" + feature.name() + ":" + context.correlationId(),
                    List.of());
        }
        return closed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int expirePendingRequests() {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.scheduler());
        int expired = write.expirePending(context, null);
        write.finish(context);
        return expired;
    }

    // ---------------------------------------------------------------- helper

    private FeatureEnablementRequest lockPendingRequest(FeatureEnablementWriteSupport.WriteContext context, long requestId,
                                                        int expectedVersion) {
        FeatureEnablementRequest found = requestMapper.selectById(requestId);
        if (found == null || !context.tenantId().equals(found.getTenantId())) {
            throw BusinessException.of(404, "error.featureEnablement.requestNotFound");
        }
        Feature feature = FeatureEnablementWriteSupport.featureOf(found.getTargetKey());
        if (feature != null) {
            write.lockStates(context, lineage(feature));
        }
        FeatureEnablementRequest request = requestMapper.selectByIdForUpdate(requestId);
        if (request == null || !"PENDING".equals(request.getStatus())) {
            throw BusinessException.of(409, "error.featureEnablement.requestNotPending");
        }
        if (request.getVersion() == null || request.getVersion() != expectedVersion) {
            throw BusinessException.of(409, "error.featureEnablement.requestVersionMismatch");
        }
        return request;
    }

    private void markApproved(FeatureEnablementWriteSupport.WriteContext context, FeatureEnablementRequest request,
                              FeatureEnablementActor approver, String reasonText) {
        UpdateWrapper<FeatureEnablementRequest> update = new UpdateWrapper<>();
        update.eq("id", request.getId()).eq("status", "PENDING").eq("version", request.getVersion())
                .set("status", "APPROVED").set("decided_by", approver.userId()).set("decided_at", context.now())
                .set("decision_reason", reasonText).set("version", request.getVersion() + 1);
        if (requestMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
    }

    private void decide(FeatureEnablementWriteSupport.WriteContext context, FeatureEnablementRequest request, String status,
                        FeatureEnablementActor actor, String reasonText, String auditCode) {
        UpdateWrapper<FeatureEnablementRequest> update = new UpdateWrapper<>();
        update.eq("id", request.getId()).eq("status", "PENDING").eq("version", request.getVersion())
                .set("status", status).set("decided_by", actor.userId()).set("decided_at", context.now())
                .set("decision_reason", reasonText).set("version", request.getVersion() + 1);
        if (requestMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        write.event(context, "REQUEST", request.getTargetKey(), request.getId(), status, "PENDING", status, null, null);
        write.audit(context, auditCode, request.getId(), "{\"request\":" + request.getId() + ",\"status\":\"PENDING\"}",
                "{\"request\":" + request.getId() + ",\"status\":\"" + status + "\"}");
        context.markChanged(FeatureEnablementWriteSupport.featureOf(request.getTargetKey()));
    }

    private void requirePrivacyAdminForTarget(FeatureEnablementRequest request, FeatureEnablementActor actor) {
        Feature feature = FeatureEnablementWriteSupport.featureOf(request.getTargetKey());
        if (feature != null) {
            actorPolicy.requirePrivacyAdminIfRequired(catalog.unit(feature).privacyUnit(), actor);
        }
    }

    private FeatureEvaluationContext evaluationContext(FeatureEnablementWriteSupport.WriteContext context,
                                                       FeatureEnablementSnapshot snapshot,
                                                       FeatureEvaluationContext.Purpose purpose, Set<Feature> openAfterChange,
                                                       boolean remote) {
        return new FeatureEvaluationContext(context.tenantId(), context.today(), write.clock(), snapshot, purpose,
                openAfterChange, remote);
    }

    private void requireMasterAllowed() {
        if (properties == null || !properties.isAllowed()) {
            throw BusinessException.of(409, "error.featureEnablement.masterClosed");
        }
    }

    private static Set<Feature> lineage(Feature feature) {
        Set<Feature> lineage = EnumSet.of(feature);
        for (Feature current = feature; current.parent().isPresent(); current = current.parent().get()) {
            lineage.add(current.parent().get());
        }
        return lineage;
    }

    private static void requireFeature(Feature feature) {
        if (feature == null) {
            throw BusinessException.of(400, "error.featureEnablement.unknownUnit");
        }
    }

    private static String optionalText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > REASON_TEXT_MAX) {
            throw BusinessException.of(400, "error.featureEnablement.reasonTooLong", REASON_TEXT_MAX);
        }
        return text.trim();
    }

    private static String requiredText(String text) {
        String value = optionalText(text);
        if (value == null) {
            throw BusinessException.of(400, "error.featureEnablement.reasonRequired");
        }
        return value;
    }

    /** 閉鎖の理由の code（必須。{@code closed_reason} は code の列で、自由記述を書かない）。 */
    static String requiredReasonCode(String reason) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.of(400, "error.featureEnablement.reasonRequired");
        }
        String code = reason.trim();
        if (!REASON_CODE.matcher(code).matches()) {
            throw BusinessException.of(400, "error.featureEnablement.reasonCodeInvalid");
        }
        return code;
    }

    private static String codes(FeatureEvaluation evaluation) {
        return String.join(",", evaluation.blocking().stream().map(FeatureConditionCheck::code).toList());
    }

    private static Object[] args(Feature feature) {
        return new Object[]{feature.name(), feature.displayName()};
    }

    private static Object[] targetArgs(FeatureEnablementRequest request) {
        Feature feature = FeatureEnablementWriteSupport.featureOf(request.getTargetKey());
        return feature == null ? new Object[]{request.getTargetKey(), request.getTargetKey()} : args(feature);
    }
}
