package com.ses.privacy.production.retention.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.privacy.RetentionPolicy;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyDecisionStatus;
import com.ses.privacy.foundation.PolicyEvaluationQuery;
import com.ses.privacy.foundation.PolicyExplanationResult;
import com.ses.privacy.foundation.PolicyFailureReason;
import com.ses.privacy.foundation.PolicyReadonlyQueryPort;
import com.ses.privacy.production.PrivacyAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * NF07 policy 説明API（{@code /api/privacy/policies}）の読み取り専用 service（W3-14、production-bridge-phased-plan.md §3.1）。
 *
 * <p>全ての入口で {@link PrivacyAccessGuard} を通す（公開スイッチ OFF は404、{@code privacy.policy.view} が無ければ403）。
 * 一覧・詳細は {@code m_retention_policy} の登録情報だけを返し、envelope の bytes・署名・法的な値は返さない。
 * policy が使えるかどうかは {@link PolicyReadonlyQueryPort} の説明照会だけで決まり、未知・未承認・検証不能・
 * 照会部品の欠落・例外は全て {@code APPROVED_ACTIVE} 以外に閉じる（fail-closed）。prod 以外の profile の照会 port は
 * 常に {@code POLICY_UNKNOWN} を返す。
 *
 * <p>本 service は書き込みを行わない。照会者の tenant は {@code AuthorizationService} が group を評価する設定tenantと
 * 一致しなければならない（別tenantの group で privacy 権限を判定させない）。
 */
@Slf4j
@Service
public class PrivacyPolicyExplanationService {

    static final String VIEW_ACTION = "privacy.policy.view";
    /** 照会keyの文字種（code値だけ。自由文や区切り文字の混入を拒否する）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+");

    private final PrivacyAccessGuard accessGuard;
    private final ObjectProvider<PolicyReadonlyQueryPort> queryPortProvider;
    private final RetentionPolicyMapper policyMapper;
    private final OidcSecurityProperties oidcProperties;

    public PrivacyPolicyExplanationService(PrivacyAccessGuard accessGuard,
                                           ObjectProvider<PolicyReadonlyQueryPort> queryPortProvider,
                                           RetentionPolicyMapper policyMapper,
                                           OidcSecurityProperties oidcProperties) {
        this.accessGuard = accessGuard;
        this.queryPortProvider = queryPortProvider;
        this.policyMapper = policyMapper;
        this.oidcProperties = oidcProperties;
    }

    /** 登録済み policy の一覧（tenant内、id順、page size は {@link PageUtils#safePage} で制限）。 */
    public IPage<PrivacyPolicySummary> list(long current, long size) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, VIEW_ACTION);
        String tenantId = requireCallerTenant();
        Page<RetentionPolicy> page = PageUtils.safePage(current, size);
        try {
            return policyMapper.selectPage(page, summaryQuery()
                            .eq(RetentionPolicy::getTenantId, tenantId)
                            .orderByAsc(RetentionPolicy::getId))
                    .convert(PrivacyPolicyExplanationService::toSummary);
        } catch (RuntimeException e) {
            log.warn("[privacy] policy一覧を照会できません", e);
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
    }

    /** 登録済み policy の詳細と、その key に対する説明照会の結果。 */
    public PrivacyPolicyDetail detail(long id) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, VIEW_ACTION);
        String tenantId = requireCallerTenant();
        RetentionPolicy row;
        try {
            row = policyMapper.selectOne(summaryQuery()
                    .eq(RetentionPolicy::getTenantId, tenantId)
                    .eq(RetentionPolicy::getId, id));
        } catch (RuntimeException e) {
            log.warn("[privacy] policy詳細を照会できません", e);
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        if (row == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        PrivacyPolicyExplanation evaluation = evaluate(tenantId, row.getModuleCode(), row.getDataElementId(),
                row.getTargetAction(), row.getTargetProvider());
        return new PrivacyPolicyDetail(toSummary(row), evaluation);
    }

    /** key を指定した説明照会。 */
    public PrivacyPolicyExplanation explain(String moduleCode, String dataElementId, String targetAction,
                                            String targetProvider) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, VIEW_ACTION);
        String tenantId = requireCallerTenant();
        String module = requireCode(moduleCode, 50);
        String element = requireCode(dataElementId, 128);
        String action = requireCode(targetAction, 50);
        String provider = requireCode(targetProvider, 100);
        return evaluate(tenantId, module, element, action, provider);
    }

    private PrivacyPolicyExplanation evaluate(String tenantId, String moduleCode, String dataElementId,
                                              String targetAction, String targetProvider) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        PolicyEvaluationQuery query = new PolicyEvaluationQuery(tenantId, Long.toString(userId), moduleCode,
                dataElementId, targetAction, targetProvider);
        PolicyReadonlyQueryPort port = queryPortProvider.getIfAvailable();
        PolicyExplanationResult result;
        if (port == null) {
            result = unknown(query, "policy照会の部品が構成されていません");
        } else {
            try {
                result = port.evaluate(query);
            } catch (RuntimeException e) {
                log.warn("[privacy] policy説明照会に失敗したためPOLICY_UNKNOWNとして扱います", e);
                result = unknown(query, "policy照会を完了できません");
            }
            if (result == null || result.status() == null) {
                result = unknown(query, "policy照会の結果を確認できません");
            }
        }
        return PrivacyPolicyExplanation.from(result);
    }

    /** 照会者の tenant（principal に束縛された値）。設定tenantと一致しなければ403。 */
    private String requireCallerTenant() {
        String callerTenant = SecurityUtils.currentTenantId();
        String authorizationTenant = StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        if (!StringUtils.hasText(callerTenant) || !authorizationTenant.equals(callerTenant)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return callerTenant;
    }

    private static String requireCode(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw BusinessException.of(400, "error.privacy.invalidQuery");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength || !CODE_PATTERN.matcher(trimmed).matches()) {
            throw BusinessException.of(400, "error.privacy.invalidQuery");
        }
        return trimmed;
    }

    /** 一覧・詳細で読む列（envelope の bytes・署名は読まない）。 */
    private static LambdaQueryWrapper<RetentionPolicy> summaryQuery() {
        return new LambdaQueryWrapper<RetentionPolicy>()
                .select(RetentionPolicy::getId, RetentionPolicy::getPolicyId, RetentionPolicy::getPolicyRevision,
                        RetentionPolicy::getModuleCode, RetentionPolicy::getDataElementId,
                        RetentionPolicy::getTargetAction, RetentionPolicy::getTargetProvider,
                        RetentionPolicy::getStatus, RetentionPolicy::getEnvelopeDigest,
                        RetentionPolicy::getRegisteredAt, RetentionPolicy::getActivatedAt);
    }

    private static PrivacyPolicySummary toSummary(RetentionPolicy row) {
        return new PrivacyPolicySummary(row.getId(), row.getPolicyId(), row.getPolicyRevision(), row.getModuleCode(),
                row.getDataElementId(), row.getTargetAction(), row.getTargetProvider(), row.getStatus(),
                row.getEnvelopeDigest(), row.getRegisteredAt(), row.getActivatedAt());
    }

    private static PolicyExplanationResult unknown(PolicyEvaluationQuery query, String explanation) {
        return new PolicyExplanationResult(PolicyDecisionStatus.POLICY_UNKNOWN, PolicyFailureReason.STORE_UNAVAILABLE,
                null, null, query.dataElementId(), query.targetAction(), query.targetProvider(), null,
                Optional.empty(), Optional.empty(), explanation);
    }
}
