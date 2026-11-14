package com.ses.privacy.production.retention.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.privacy.RetentionPolicy;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.CurrentPolicySnapshot;
import com.ses.privacy.foundation.CurrentPolicySource;
import com.ses.privacy.foundation.PolicyLookupKey;
import com.ses.privacy.foundation.ProductionCatalogBinding;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

/**
 * application DB の {@code m_retention_policy} を使う current policy source（読み取り専用。prod でだけ構成する）。
 *
 * <p>当該 key の {@code status = 'CURRENT'} の行を1文の SELECT で読む（{@code LIMIT 2}）。0件は NOT_FOUND、
 * 2件以上は CONFLICT（生成列 + UNIQUE の current pointer があれば起きないが、起きても選ばない）、1件なら
 * 保存された canonical bytes・署名・revision・digest をそのまま返す。履歴から「最新そうなもの」を選ばず、
 * revision を比較しない。照会の失敗は UNAVAILABLE。構築時に DB へ触れず、{@code current_key} を含め書き込まない。
 */
@Slf4j
public final class DbCurrentPolicySource implements CurrentPolicySource {

    static final String STATUS_CURRENT = "CURRENT";

    private final RetentionPolicyMapper policyMapper;

    public DbCurrentPolicySource(RetentionPolicyMapper policyMapper) {
        this.policyMapper = Objects.requireNonNull(policyMapper, "policyMapper must not be null");
    }

    @Override
    public CurrentPolicySnapshot readCurrent(PolicyLookupKey key, ProductionCatalogBinding binding) {
        if (key == null || binding == null || key.legalEntityId() != PolicyLookupKey.TENANT_WIDE) {
            return CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.NOT_FOUND);
        }
        List<RetentionPolicy> rows;
        try {
            rows = policyMapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                    .select(RetentionPolicy::getId, RetentionPolicy::getPolicyId, RetentionPolicy::getPolicyRevision,
                            RetentionPolicy::getEnvelopeDigest, RetentionPolicy::getEnvelopeCanonical,
                            RetentionPolicy::getEnvelopeSignature)
                    .eq(RetentionPolicy::getTenantId, key.tenantId())
                    .eq(RetentionPolicy::getModuleCode, key.moduleCode())
                    .isNull(RetentionPolicy::getLegalEntityId)
                    .eq(RetentionPolicy::getDataElementId, key.dataElementId())
                    .eq(RetentionPolicy::getTargetAction, key.action())
                    .eq(RetentionPolicy::getTargetProvider, key.providerId())
                    .eq(RetentionPolicy::getStatus, STATUS_CURRENT)
                    .orderByAsc(RetentionPolicy::getId)
                    .last("LIMIT 2"));
        } catch (RuntimeException e) {
            log.warn("[privacy] current policyを照会できません（POLICY_UNKNOWNとして扱います）", e);
            return CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.UNAVAILABLE);
        }
        if (rows == null || rows.isEmpty()) {
            return CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.NOT_FOUND);
        }
        if (rows.size() > 1) {
            return CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.CONFLICT);
        }
        RetentionPolicy row = rows.get(0);
        return CurrentPolicySnapshot.current(row.getPolicyId(), row.getPolicyRevision(), row.getEnvelopeDigest(),
                row.getEnvelopeCanonical(), row.getEnvelopeSignature());
    }
}
