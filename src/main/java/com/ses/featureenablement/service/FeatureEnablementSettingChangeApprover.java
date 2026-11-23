package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureEnablementSetting;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionFingerprint;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementSettingMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 設定の変更の申請（{@code SETTING_CHANGE}）の承認（feature-enablement-console design §5.6・§8.4、FE-RQ-010。FE-B03）。申請の種類の
 * 拡張点（{@link FeatureEnablementRequestApprover}）の実装で、条件・指紋・値の形式の規則を申請の側
 * （{@link FeatureEnablementSettingChangeService}）と共用する。
 *
 * <ul>
 *   <li>対象は NF02 の公開範囲（{@value FeatureEnablementCatalog#SETTING_NF02_VISIBLE_SINCE}）だけ。値は offset 付き ISO-8601
 *       （{@code PortalServiceRequestVisibilityPolicy} の解釈と同じ）か未設定（{@code null}）。</li>
 *   <li>条件: 確認済みの署名 {@value #KIND_DECISION}（公開範囲の決定）が期間の内で、その構造化された値 {@code visibleSince}（空・
 *       無しは未設定の決定）が申請の値と一致する。値を設定する（未設定でない）時は {@value #KIND_INVENTORY}（棚卸し）も期間の内で要る。</li>
 *   <li>指紋は設定の key・申請の値・要る署名の {@code id:version}（{@link FeatureConditionFingerprint} の正規形）。承認の時に違えば
 *       STALE で終える。</li>
 *   <li>{@link FeatureEnablementServiceImpl#approve} が世代・申請の行を lock し、申請者本人の承認を 403 にした後に呼ぶ。申請を先に
 *       APPROVED にしてから設定の行を書く（{@code fk_fe_setting_request} の複合 FK。review P3-N7）。設定の行は lock の順 (5)。</li>
 * </ul>
 */
@Component
public class FeatureEnablementSettingChangeApprover implements FeatureEnablementRequestApprover {

    public static final String REQUEST_TYPE = "SETTING_CHANGE";
    /** 公開範囲の決定（CS Owner）と、値を設定する時の棚卸し。 */
    public static final String KIND_DECISION = "NF02_PORTAL_VISIBLE_SINCE_DECISION";
    public static final String KIND_INVENTORY = "NF02_PORTAL_INVENTORY";
    /** 承認で変えられる設定（V203 の {@code ck_fe_setting_key} と同じ）。 */
    public static final Set<String> SETTING_KEYS = Set.of(FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE);
    /** 値の列の長さ（{@code setting_value}・{@code proposed_value} は VARCHAR(100)）。 */
    static final int VALUE_MAX = 100;
    static final String CODE_PREFIX = "setting.visibleSince.";

    private final FeatureEnablementWriteSupport write;
    private final FeatureEnablementSettingMapper settingMapper;
    private final FeatureEnablementRequestMapper requestMapper;

    public FeatureEnablementSettingChangeApprover(FeatureEnablementWriteSupport write,
                                                  FeatureEnablementSettingMapper settingMapper,
                                                  FeatureEnablementRequestMapper requestMapper) {
        this.write = write;
        this.settingMapper = settingMapper;
        this.requestMapper = requestMapper;
    }

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public FeatureEnablementApprovalResult approveLocked(FeatureEnablementWriteSupport.WriteContext context,
                                                         FeatureEnablementRequest request, FeatureEnablementActor approver,
                                                         String decisionReason) {
        String settingKey = request.getTargetKey();
        if (!SETTING_KEYS.contains(settingKey)) {
            throw BusinessException.of(409, "error.featureEnablement.requestTypeUnsupported");
        }
        String value;
        try {
            value = normalize(request.getProposedValue());
        } catch (BusinessException invalid) {
            throw BusinessException.of(409, "error.featureEnablement.requestTypeUnsupported");
        }
        FeatureEnablementSnapshot snapshot = context.snapshot();
        List<FeatureConditionCheck> checks = conditions(snapshot, value, context.today());
        List<String> failing = checks.stream().filter(check -> !check.passing()).map(FeatureConditionCheck::code).toList();
        if (!failing.isEmpty()) {
            // 申請は未決のまま（失効の先行の閉鎖は commit する）
            return new FeatureEnablementApprovalResult(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED,
                    request.getId(), failing);
        }
        if (!fingerprint(snapshot, settingKey, value).equals(request.getConditionFingerprint())) {
            write.endBySystem(context, request, "STALE", "FEATURE_SETTING_CHANGE_STALE", "FINGERPRINT_CHANGED");
            return new FeatureEnablementApprovalResult(FeatureEnablementApprovalResult.Outcome.STALE, request.getId(),
                    List.of());
        }

        // 申請を先に APPROVED にする（設定の行の複合 FK が申請の申請者・決定者を参照する）
        UpdateWrapper<FeatureEnablementRequest> decided = new UpdateWrapper<>();
        decided.eq("id", request.getId()).eq("status", "PENDING").eq("version", request.getVersion())
                .set("status", "APPROVED").set("decided_by", approver.userId()).set("decided_at", context.now())
                .set("decision_reason", decisionReason).set("version", request.getVersion() + 1);
        if (requestMapper.update(null, decided) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        FeatureEnablementSetting row = settingMapper.selectByKeyForUpdate(context.tenantId(), settingKey);
        String before = row == null ? null : row.getSettingValue();
        if (row == null) {
            FeatureEnablementSetting created = new FeatureEnablementSetting();
            created.setTenantId(context.tenantId());
            created.setSettingKey(settingKey);
            created.setSettingValue(value);
            created.setVersion(0);
            created.setRequestId(request.getId());
            created.setRequestedBy(request.getRequestedBy());
            created.setApprovedBy(approver.userId());
            created.setApprovedAt(context.now());
            settingMapper.insert(created);
            row = created;
        } else {
            UpdateWrapper<FeatureEnablementSetting> update = new UpdateWrapper<>();
            update.eq("id", row.getId()).eq("version", row.getVersion())
                    .set("setting_value", value).set("version", row.getVersion() + 1).set("request_id", request.getId())
                    .set("requested_by", request.getRequestedBy()).set("approved_by", approver.userId())
                    .set("approved_at", context.now());
            if (settingMapper.update(null, update) != 1) {
                throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
            }
        }
        write.event(context, "SETTING", settingKey, row.getId(), "CHANGED", null, null, "REQUEST_" + request.getId(),
                null);
        write.audit(context, "FEATURE_SETTING_CHANGED", request.getId(),
                "{\"setting\":\"" + settingKey + "\",\"value\":" + json(before) + "}",
                "{\"setting\":\"" + settingKey + "\",\"value\":" + json(value) + ",\"request\":" + request.getId() + "}");
        context.markChanged(Feature.NF02_CUSTOMER_PORTAL);
        write.notifier().notifyAdministrators("settingChanged", new Object[]{settingKey},
                "FEATURE_ENABLEMENT:SETTING_CHANGED:" + settingKey + ":" + request.getId(), List.of());
        return FeatureEnablementApprovalResult.approved(request.getId());
    }

    // ---------------------------------------------------------------- 申請と共用する規則

    /**
     * 申請の値を正規化する（空・null は未設定＝{@code null}。前後の空白を除く）。offset 付き ISO-8601 でなければ 400
     * （{@code PortalServiceRequestVisibilityPolicy} の解釈と同じ。値そのものは返さない）。
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > VALUE_MAX || parse(trimmed) == null) {
            throw BusinessException.of(400, "error.featureEnablement.settingValueInvalid");
        }
        return trimmed;
    }

    /** 同じ公開範囲か（どちらも未設定、またはどちらも解釈でき同じ日時・offset）。 */
    public static boolean sameValue(String left, String right) {
        String a = left == null || left.isBlank() ? null : left.trim();
        String b = right == null || right.isBlank() ? null : right.trim();
        if (a == null || b == null) {
            return a == null && b == null;
        }
        OffsetDateTime parsedA = parse(a);
        return parsedA != null && parsedA.equals(parse(b));
    }

    /** 条件（決定の署名の期間と値の一致、値を設定する時は棚卸しの署名の期間）。 */
    public static List<FeatureConditionCheck> conditions(FeatureEnablementSnapshot snapshot, String value, LocalDate today) {
        List<FeatureConditionCheck> checks = new ArrayList<>();
        FeatureEnablementSnapshot.ConfirmedSignOff decision = snapshot.signOffs().get(KIND_DECISION);
        FeatureConditionCheck decisionPeriod = period("decision", decision, today);
        if (decisionPeriod.passing()) {
            String decided;
            try {
                decided = FeatureSignOffRegistrar.structuredMap(decision.structuredValues()).get("visibleSince");
            } catch (RuntimeException unreadable) {
                decided = "\u0000unreadable";
            }
            checks.add(sameValue(decided, value)
                    ? FeatureConditionCheck.satisfied(CODE_PREFIX + "decision.valid", FeatureConditionCheck.Category.SETTING,
                    decision.expiresOn())
                    : FeatureConditionCheck.unsatisfied(CODE_PREFIX + "decision.valueMismatch",
                    FeatureConditionCheck.Category.SETTING, false));
        } else {
            checks.add(decisionPeriod);
        }
        if (value != null) {
            checks.add(period("inventory", snapshot.signOffs().get(KIND_INVENTORY), today));
        }
        return List.copyOf(checks);
    }

    /** 指紋（設定の key・値・要る署名の {@code id:version}）。 */
    public static String fingerprint(FeatureEnablementSnapshot snapshot, String settingKey, String value) {
        Map<String, String> signOffs = new TreeMap<>();
        signOffs.put(KIND_DECISION, reference(snapshot.signOffs().get(KIND_DECISION)));
        if (value != null) {
            signOffs.put(KIND_INVENTORY, reference(snapshot.signOffs().get(KIND_INVENTORY)));
        }
        return FeatureConditionFingerprint.sha256(FeatureConditionFingerprint.canonical(settingKey, REQUEST_TYPE, value,
                signOffs, Map.of(), null));
    }

    /** 条件の要約（code と成否だけ。値を含めない）。 */
    public static String summary(List<FeatureConditionCheck> checks) {
        return checks.stream().map(check -> check.code() + "=" + check.status().name()).collect(Collectors.joining(";"));
    }

    private static FeatureConditionCheck period(String name, FeatureEnablementSnapshot.ConfirmedSignOff signOff,
                                                LocalDate today) {
        String prefix = CODE_PREFIX + name;
        if (signOff == null) {
            return FeatureConditionCheck.unsatisfied(prefix + ".missing", FeatureConditionCheck.Category.SETTING, false);
        }
        if (signOff.signedOn().isAfter(today)) {
            return FeatureConditionCheck.unsatisfied(prefix + ".signedOn.future", FeatureConditionCheck.Category.SETTING,
                    true);
        }
        if (!today.isBefore(signOff.expiresOn())) {
            return FeatureConditionCheck.unsatisfied(prefix + ".expiresOn.expired", FeatureConditionCheck.Category.SETTING,
                    true);
        }
        return FeatureConditionCheck.satisfied(prefix + ".valid", FeatureConditionCheck.Category.SETTING,
                signOff.expiresOn());
    }

    private static String reference(FeatureEnablementSnapshot.ConfirmedSignOff signOff) {
        return signOff == null ? "-" : signOff.id() + ":" + signOff.version();
    }

    private static OffsetDateTime parse(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException | NullPointerException invalid) {
            return null;
        }
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + Objects.requireNonNull(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
