package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.IdentityProvider;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.service.AuditLogService;
import com.ses.service.IdentityProviderRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * OIDCログイン（{@code OidcLoginUserService}）が照合する {@code m_identity_provider} を、起動設定の固定metadataから登録する。
 *
 * <p>空DBの本番には登録経路が無く、OIDCログインも外部identityの承認（{@code ExternalIdentityProvisioningService}）も
 * 行えなかったため、本番初回bootstrap（{@code deploy-runbook.md} §5）の手順として追加した。
 * 登録内容はrequestから受け取らず、{@code app.security.oidc.tenant-id / issuer-uri / client-id} だけから作る
 * （OIDCログイン時の照合値と必ず一致させ、任意のissuerを登録させない）。client secretはDBへ保存しない。
 */
@Service
@RequiredArgsConstructor
public class IdentityProviderRegistrationServiceImpl implements IdentityProviderRegistrationService {

    private static final String PROVIDER_TYPE = "OIDC";

    private final IdentityProviderMapper identityProviderMapper;
    private final OidcSecurityProperties properties;
    private final AuditLogService auditLogService;

    @Override
    public List<IdentityProvider> list() {
        requireAdmin();
        String tenantId = configuredTenant();
        if (tenantId == null) {
            return List.of();
        }
        return identityProviderMapper.selectList(new LambdaQueryWrapper<IdentityProvider>()
                .eq(IdentityProvider::getTenantId, tenantId)
                .orderByAsc(IdentityProvider::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IdentityProvider registerConfiguredOidcProvider() {
        requireAdmin();
        String tenantId = configuredTenant();
        String issuer = trimmed(properties.getIssuerUri());
        String clientId = trimmed(properties.getClientId());
        if (!properties.isEnabled() || tenantId == null || issuer == null || clientId == null) {
            throw BusinessException.of(400, "error.identity.providerConfigMissing");
        }
        IdentityProvider existing = identityProviderMapper.selectOne(new LambdaQueryWrapper<IdentityProvider>()
                .eq(IdentityProvider::getTenantId, tenantId)
                .eq(IdentityProvider::getProviderType, PROVIDER_TYPE)
                .eq(IdentityProvider::getIssuerUri, issuer)
                .last("LIMIT 1"));
        if (existing != null) {
            if (Integer.valueOf(1).equals(existing.getEnabled())) {
                return existing;
            }
            // 無効化された登録は自動で再有効化しない（無効化は管理判断のため）。
            throw BusinessException.of(409, "error.identity.providerNotAllowed");
        }
        IdentityProvider provider = new IdentityProvider();
        provider.setTenantId(tenantId);
        provider.setProviderType(PROVIDER_TYPE);
        provider.setIssuerUri(issuer);
        provider.setClientId(clientId);
        provider.setEnabled(1);
        try {
            if (identityProviderMapper.insert(provider) != 1) {
                throw BusinessException.of(409, "error.identity.linkConflict");
            }
        } catch (DuplicateKeyException e) {
            throw BusinessException.of(409, "error.identity.linkConflict");
        }
        // 監査が確定しない場合は登録ごとrollbackする。
        auditLogService.recordRequired(SecurityUtils.currentUsername(), "SECURITY",
                "/api/identity-providers/" + provider.getId(), 201, "IDENTITY_PROVIDER_REGISTERED", true);
        return provider;
    }

    private static void requireAdmin() {
        if (!StatusConstants.ROLE_ADMIN.equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    private String configuredTenant() {
        return trimmed(properties.getTenantId());
    }

    private static String trimmed(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
