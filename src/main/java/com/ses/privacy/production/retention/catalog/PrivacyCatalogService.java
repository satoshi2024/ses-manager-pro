package com.ses.privacy.production.retention.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyCoverageReport;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * NF07 data element catalog の照会（{@code GET /api/privacy/catalog}、production-bridge-phased-plan.md §3.2）。
 *
 * <p>入口で {@link PrivacyAccessGuard} を通す（公開スイッチ OFF は404、{@code privacy.catalog.view} が無ければ403）。
 * 照会者の tenant の catalog 行（{@code m_pii_data_element}）を読み、registry の provider と突き合わせた
 * {@link PrivacyCoverageReport} を返す。provider の照会（本人の行の読取り）は呼ばない。書き込みは行わない。
 * 照会者の tenant は {@code AuthorizationService} が group を評価する設定 tenant と一致しなければ403。
 */
@Slf4j
@Service
public class PrivacyCatalogService {

    static final String VIEW_ACTION = "privacy.catalog.view";

    private final PrivacyAccessGuard accessGuard;
    private final PiiDataElementMapper dataElementMapper;
    private final ProductionPrivacyProviderRegistry registry;
    private final OidcSecurityProperties oidcProperties;

    public PrivacyCatalogService(PrivacyAccessGuard accessGuard, PiiDataElementMapper dataElementMapper,
                                 ProductionPrivacyProviderRegistry registry, OidcSecurityProperties oidcProperties) {
        this.accessGuard = accessGuard;
        this.dataElementMapper = dataElementMapper;
        this.registry = registry;
        this.oidcProperties = oidcProperties;
    }

    /** 照会者の tenant の catalog と provider の対応表。 */
    public PrivacyCoverageReport catalog() {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, VIEW_ACTION);
        String tenantId = requireCallerTenant();
        List<PiiDataElement> rows;
        try {
            rows = dataElementMapper.selectList(new LambdaQueryWrapper<PiiDataElement>()
                    .select(PiiDataElement::getId, PiiDataElement::getTenantId, PiiDataElement::getDataElementId,
                            PiiDataElement::getStorageKind, PiiDataElement::getStorageRef,
                            PiiDataElement::getProviderId, PiiDataElement::getSubjectAttribution,
                            PiiDataElement::getOwnerState, PiiDataElement::getInventoryStatus,
                            PiiDataElement::getInventoryRef)
                    .eq(PiiDataElement::getTenantId, tenantId)
                    .orderByAsc(PiiDataElement::getDataElementId));
        } catch (RuntimeException e) {
            log.warn("[privacy] data element catalogを照会できません", e);
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        return PrivacyCoverageReport.build(tenantId, rows, registry);
    }

    /** 照会者の tenant（principal に束縛された値）。設定 tenant と一致しなければ403。 */
    private String requireCallerTenant() {
        String callerTenant = SecurityUtils.currentTenantId();
        String authorizationTenant = StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        if (!StringUtils.hasText(callerTenant) || !authorizationTenant.equals(callerTenant)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return callerTenant;
    }
}
