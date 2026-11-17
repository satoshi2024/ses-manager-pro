package com.ses.privacy.production.retention.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.entity.privacy.RetentionPolicy;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyLookupKey;
import com.ses.privacy.foundation.ProductionCatalogBinding;
import com.ses.privacy.foundation.ProductionPolicyCatalogPort;

import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * application DB の F1 表を使う production policy catalog（読み取り専用。prod でだけ構成する）。
 *
 * <p>解決規則（第1期）:
 * <ul>
 *   <li>data element が {@code m_pii_data_element} に tenant 付きで1件登録されていること。</li>
 *   <li>key（tenant・module・data element・action・provider、tenant-wide）に対し、{@code REVOKED} 以外の
 *       {@code m_retention_policy} の policy_id がちょうど1種類であること。0種類は未登録、2種類以上は曖昧として解決しない。</li>
 *   <li>法人単位の key（legalEntityId != 0）は P-LE 未決のため解決しない。</li>
 * </ul>
 * scope は module code とする（署名 envelope の scope と照合される。NF07-REG は envelope の scope を module code として登録する）。
 * 構築時に DB へ触れず、照会のたびに読む。書き込みは行わない。正本 store の所有・改竄検知（P-SRC）は未署名のため、
 * 本 catalog の結果は本番 currentness の証明ではない。
 */
public final class DbProductionPolicyCatalog implements ProductionPolicyCatalogPort {

    private static final String STATUS_REVOKED = "REVOKED";

    private final PiiDataElementMapper dataElementMapper;
    private final RetentionPolicyMapper policyMapper;

    public DbProductionPolicyCatalog(PiiDataElementMapper dataElementMapper, RetentionPolicyMapper policyMapper) {
        this.dataElementMapper = Objects.requireNonNull(dataElementMapper, "dataElementMapper must not be null");
        this.policyMapper = Objects.requireNonNull(policyMapper, "policyMapper must not be null");
    }

    @Override
    public Optional<ProductionCatalogBinding> resolve(PolicyLookupKey key) {
        if (key == null || key.legalEntityId() != PolicyLookupKey.TENANT_WIDE) {
            return Optional.empty();
        }
        Long registered = dataElementMapper.selectCount(new LambdaQueryWrapper<PiiDataElement>()
                .eq(PiiDataElement::getTenantId, key.tenantId())
                .eq(PiiDataElement::getDataElementId, key.dataElementId()));
        if (registered == null || registered != 1L) {
            return Optional.empty();
        }
        TreeSet<String> policyIds = new TreeSet<>();
        policyMapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                        .select(RetentionPolicy::getPolicyId)
                        .eq(RetentionPolicy::getTenantId, key.tenantId())
                        .eq(RetentionPolicy::getModuleCode, key.moduleCode())
                        .isNull(RetentionPolicy::getLegalEntityId)
                        .eq(RetentionPolicy::getDataElementId, key.dataElementId())
                        .eq(RetentionPolicy::getTargetAction, key.action())
                        .eq(RetentionPolicy::getTargetProvider, key.providerId())
                        .ne(RetentionPolicy::getStatus, STATUS_REVOKED))
                .stream()
                .map(RetentionPolicy::getPolicyId)
                .filter(Objects::nonNull)
                .forEach(policyIds::add);
        if (policyIds.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(new ProductionCatalogBinding(policyIds.first(), key.moduleCode(),
                ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING, key.dataElementId(), key.action(),
                key.providerId(), PolicyLookupKey.TENANT_WIDE));
    }
}
