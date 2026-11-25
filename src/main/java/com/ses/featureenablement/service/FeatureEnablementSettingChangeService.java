package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureEnablementSetting;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementSettingMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 設定の変更の申請（{@code SETTING_CHANGE}。feature-enablement-console design §5.6・§8.4、FE-RQ-010。FE-B03）と、画面「設定」tab の
 * 読取り。承認は申請の種類の拡張点 {@link FeatureEnablementSettingChangeApprover}（{@link FeatureEnablementService#approve} が呼ぶ）、
 * 却下・取下げは {@link FeatureEnablementService} の共通の処理で行う（{@link FeatureEnablementServiceImpl} は変えない）。
 *
 * <ul>
 *   <li>行為者は有効な管理者（{@link FeatureEnablementActorPolicy}）。総スイッチが閉なら 409。</li>
 *   <li>値は offset 付き ISO-8601 か未設定（空）。それ以外は 400。今の値と同じ値は 409。</li>
 *   <li>世代の行を lock して読み直した状態で、決定の署名の値との一致（と値を設定する時の棚卸し）を確かめ、成り立たなければ 409。
 *       同じ設定の期限切れの未決を先に EXPIRED にし、未決は1件だけ（{@code pending_slot}）。</li>
 * </ul>
 */
@Service
public class FeatureEnablementSettingChangeService {

    private final FeatureEnablementWriteSupport write;
    private final FeatureEnablementActorPolicy actorPolicy;
    private final FeatureEnablementRequestMapper requestMapper;
    private final FeatureEnablementSettingMapper settingMapper;
    private final FeatureEnablementSnapshotHolder holder;
    private final FeatureEnablementTenant tenant;
    private final FeatureEnablementProperties properties;
    private final Clock clock;
    private final FeatureEnablementStateSource stateSource;

    public FeatureEnablementSettingChangeService(FeatureEnablementWriteSupport write,
                                                 FeatureEnablementActorPolicy actorPolicy,
                                                 FeatureEnablementRequestMapper requestMapper,
                                                 FeatureEnablementSettingMapper settingMapper,
                                                 FeatureEnablementSnapshotHolder holder, FeatureEnablementTenant tenant,
                                                 FeatureEnablementProperties properties, Clock clock,
                                                 FeatureEnablementStateSource stateSource) {
        this.write = write;
        this.actorPolicy = actorPolicy;
        this.requestMapper = requestMapper;
        this.settingMapper = settingMapper;
        this.holder = holder;
        this.tenant = tenant;
        this.properties = properties;
        this.clock = clock;
        this.stateSource = stateSource;
    }

    /**
     * 画面「設定」tab の1行（値は秘密ではない。署名者の氏名を含めない）。{@code effective} は承認した設定が portal の判定に効く源か
     * （{@code source=database} のとき {@code true}。{@code source=properties}（dev の既定）では承認しても portal は property の値のまま。
     * review rb-fe-b-656d71b8 の P3-5・FE-E02）。
     */
    public record SettingView(String key, String value, Integer version, Long requestId, Long requestedBy, Long approvedBy,
                              LocalDateTime approvedAt, boolean decisionConfirmed, String decisionValue,
                              LocalDate decisionExpiresOn, boolean inventoryConfirmed, Long pendingRequestId,
                              String pendingValue, boolean pendingMine, boolean effective) {
    }

    /** 設定の変更を申請する（値は null・空で未設定へ戻す）。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureEnablementRequest request(String settingKey, String value, String reason, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
        if (settingKey == null || !FeatureEnablementSettingChangeApprover.SETTING_KEYS.contains(settingKey)) {
            throw BusinessException.of(400, "error.featureEnablement.settingUnknown");
        }
        String normalized = FeatureEnablementSettingChangeApprover.normalize(value);
        if (properties == null || !properties.isAllowed()) {
            throw BusinessException.of(409, "error.featureEnablement.masterClosed");
        }
        String reasonText = reason == null || reason.isBlank() ? null : reason.trim();
        if (reasonText != null && reasonText.length() > FeatureEnablementServiceImpl.REASON_TEXT_MAX) {
            throw BusinessException.of(400, "error.featureEnablement.reasonTooLong",
                    FeatureEnablementServiceImpl.REASON_TEXT_MAX);
        }

        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        write.expirePending(context, settingKey);
        FeatureEnablementSnapshot snapshot = context.reread();
        if (FeatureEnablementSettingChangeApprover.sameValue(snapshot.setting(settingKey).orElse(null), normalized)) {
            throw BusinessException.of(409, "error.featureEnablement.settingUnchanged");
        }
        List<FeatureConditionCheck> checks = FeatureEnablementSettingChangeApprover.conditions(snapshot, normalized,
                context.today());
        List<String> failing = checks.stream().filter(check -> !check.passing()).map(FeatureConditionCheck::code).toList();
        if (!failing.isEmpty()) {
            throw BusinessException.of(409, "error.featureEnablement.conditionsUnsatisfied", String.join(",", failing));
        }

        FeatureEnablementRequest request = new FeatureEnablementRequest();
        request.setTenantId(context.tenantId());
        request.setRequestType(FeatureEnablementSettingChangeApprover.REQUEST_TYPE);
        request.setTargetKey(settingKey);
        request.setProposedValue(normalized);
        request.setStatus("PENDING");
        request.setConditionFingerprint(FeatureEnablementSettingChangeApprover.fingerprint(snapshot, settingKey,
                normalized));
        request.setConditionSummary(FeatureEnablementSettingChangeApprover.summary(checks));
        request.setReason(reasonText);
        request.setRequestedBy(checked.userId());
        request.setRequestedAt(context.now());
        request.setExpiresAt(context.now().plus(FeatureEnablementService.REQUEST_TTL));
        request.setVersion(0);
        try {
            requestMapper.insert(request);
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.of(409, "error.featureEnablement.pendingExists");
        }
        write.event(context, "REQUEST", settingKey, request.getId(), "REQUESTED", null, "PENDING", null,
                request.getConditionSummary());
        write.audit(context, "FEATURE_SETTING_REQUESTED", request.getId(), null,
                "{\"setting\":\"" + settingKey + "\",\"request\":" + request.getId() + ",\"status\":\"PENDING\"}");
        context.markChanged(null);
        write.finish(context);
        write.notifier().notifyAdministrators("settingChangeRequested", new Object[]{settingKey},
                "FEATURE_ENABLEMENT:SETTING_CHANGE_REQUESTED:" + settingKey + ":" + request.getId(),
                List.of(checked.userId()));
        return request;
    }

    /** 画面「設定」tab の一覧（承認で変える設定の全て。今の値・決定の署名の値・未決の申請）。 */
    public List<SettingView> settings(FeatureEnablementActor actor) {
        if (actor == null) {
            throw BusinessException.of(403, FeatureEnablementActorPolicy.ACTOR_NOT_ALLOWED);
        }
        String tenantId = tenant.tenantId();
        Map<String, FeatureEnablementSetting> rows = settingMapper.selectByTenant(tenantId).stream()
                .collect(Collectors.toMap(FeatureEnablementSetting::getSettingKey, Function.identity(), (a, b) -> a));
        FeatureEnablementSnapshot snapshot = holder.get();
        LocalDate today = FeatureEvaluationContext.businessDate(clock);
        FeatureEnablementSnapshot.ConfirmedSignOff decision =
                snapshot.signOffs().get(FeatureEnablementSettingChangeApprover.KIND_DECISION);
        FeatureEnablementSnapshot.ConfirmedSignOff inventory =
                snapshot.signOffs().get(FeatureEnablementSettingChangeApprover.KIND_INVENTORY);
        String decisionValue = null;
        if (decision != null) {
            try {
                decisionValue = FeatureSignOffRegistrar.structuredMap(decision.structuredValues()).get("visibleSince");
            } catch (RuntimeException unreadable) {
                decisionValue = null;
            }
        }
        List<SettingView> views = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(FeatureEnablementSettingChangeApprover.SETTING_KEYS)) {
            FeatureEnablementSetting row = rows.get(key);
            FeatureEnablementRequest pending = requestMapper.selectPendingByTarget(tenantId, key);
            views.add(new SettingView(key, row == null ? null : row.getSettingValue(), row == null ? null : row.getVersion(),
                    row == null ? null : row.getRequestId(), row == null ? null : row.getRequestedBy(),
                    row == null ? null : row.getApprovedBy(), row == null ? null : row.getApprovedAt(),
                    valid(decision, today), decisionValue == null || decisionValue.isBlank() ? null : decisionValue,
                    decision == null ? null : decision.expiresOn(), valid(inventory, today),
                    pending == null ? null : pending.getId(), pending == null ? null : pending.getProposedValue(),
                    pending != null && actor.userId().equals(pending.getRequestedBy()), effective()));
        }
        return List.copyOf(views);
    }

    /** 承認した設定が portal の判定に効く源か（DB の源）。 */
    private boolean effective() {
        return stateSource != null && stateSource.database();
    }

    private static boolean valid(FeatureEnablementSnapshot.ConfirmedSignOff signOff, LocalDate today) {
        return signOff != null && !signOff.signedOn().isAfter(today) && today.isBefore(signOff.expiresOn());
    }
}
