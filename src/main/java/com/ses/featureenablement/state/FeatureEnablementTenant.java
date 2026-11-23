package com.ses.featureenablement.state;

import com.ses.config.OidcSecurityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 機能の開放の tenant（design §2.1。設定の tenant {@code OIDC_TENANT_ID}、未設定なら {@code default}）。開放状態は tenant ごとに持つが、
 * 現在は単一 tenant（SRE-1）。
 */
@Component
public class FeatureEnablementTenant {

    public static final String DEFAULT_TENANT = "default";

    private final ObjectProvider<OidcSecurityProperties> oidcProperties;

    public FeatureEnablementTenant(ObjectProvider<OidcSecurityProperties> oidcProperties) {
        this.oidcProperties = oidcProperties;
    }

    /** 設定の tenant。 */
    public String tenantId() {
        OidcSecurityProperties properties = oidcProperties == null ? null : oidcProperties.getIfAvailable();
        String configured = properties == null ? null : properties.getTenantId();
        return StringUtils.hasText(configured) ? configured.trim() : DEFAULT_TENANT;
    }

    /** 固定の tenant（試験と Spring 管理外の部品）。 */
    public static FeatureEnablementTenant fixed(String tenantId) {
        return new FeatureEnablementTenant(null) {
            @Override
            public String tenantId() {
                return tenantId;
            }
        };
    }
}
