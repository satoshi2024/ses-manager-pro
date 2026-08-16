package com.ses.service.compliance;

import com.ses.config.OidcSecurityProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * R23-P1-01 P1-2: compliance gateのtenant解決を一箇所に集約する。
 * 全serviceが "default" をハードコードせず、本resolver経由で現tenantを解決する。
 * （単一tenant構成ではOidcSecurityProperties.tenantId＝"default"だが、
 *  multi-tenant導入時はここだけ変更すれば全compliance境界へ波及する。）
 */
@Component
public class ComplianceTenantResolver {

    private final OidcSecurityProperties oidcProperties;

    public ComplianceTenantResolver(OidcSecurityProperties oidcProperties) {
        this.oidcProperties = oidcProperties;
    }

    public String currentTenantId() {
        String tenantId = oidcProperties.getTenantId();
        return StringUtils.hasText(tenantId) ? tenantId : "default";
    }
}
