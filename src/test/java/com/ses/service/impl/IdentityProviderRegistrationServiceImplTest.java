package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.IdentityProvider;
import com.ses.entity.SysUser;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 起動設定の固定metadataからOIDC Identity Providerを登録する（本番初回bootstrap §5.3）。 */
class IdentityProviderRegistrationServiceImplTest {

    private final IdentityProviderMapper mapper = mock(IdentityProviderMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OidcSecurityProperties properties = new OidcSecurityProperties();
    private IdentityProviderRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setTenantId("default");
        properties.setIssuerUri("https://idp.example/tenant/v2.0");
        properties.setClientId("ses-client");
        properties.setClientSecret("test-only-client-credential");
        service = new IdentityProviderRegistrationServiceImpl(mapper, properties, auditLogService);
        authenticate("管理者");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 設定のtenant_issuer_client_idだけから登録しsecretを保存せず必須監査を残す() {
        doAnswer(invocation -> {
            ((IdentityProvider) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(mapper).insert(any(IdentityProvider.class));

        IdentityProvider provider = service.registerConfiguredOidcProvider();

        ArgumentCaptor<IdentityProvider> inserted = ArgumentCaptor.forClass(IdentityProvider.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId()).isEqualTo("default");
        assertThat(inserted.getValue().getProviderType()).isEqualTo("OIDC");
        assertThat(inserted.getValue().getIssuerUri()).isEqualTo("https://idp.example/tenant/v2.0");
        assertThat(inserted.getValue().getClientId()).isEqualTo("ses-client");
        assertThat(inserted.getValue().getEnabled()).isEqualTo(1);
        assertThat(inserted.getValue().getEncryptedSecretRef()).as("client secretはDBへ保存しない").isNull();
        assertThat(provider.getId()).isEqualTo(7L);
        verify(auditLogService).recordRequired("bootstrap-admin", "SECURITY", "/api/identity-providers/7", 201,
                "IDENTITY_PROVIDER_REGISTERED", true);
    }

    @Test
    void 同じissuerの有効な登録があればそれを返し二重登録しない() {
        IdentityProvider existing = new IdentityProvider();
        existing.setId(3L);
        existing.setEnabled(1);
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.registerConfiguredOidcProvider()).isSameAs(existing);
        verify(mapper, never()).insert(any(IdentityProvider.class));
        verify(auditLogService, never()).recordRequired(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyBoolean());
    }

    @Test
    void 無効化された登録は自動で再有効化しない() {
        IdentityProvider disabled = new IdentityProvider();
        disabled.setId(3L);
        disabled.setEnabled(0);
        when(mapper.selectOne(any())).thenReturn(disabled);

        assertThatThrownBy(service::registerConfiguredOidcProvider)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(409);
        verify(mapper, never()).insert(any(IdentityProvider.class));
    }

    @Test
    void OIDC設定が不足していれば登録しない() {
        for (Runnable broken : List.<Runnable>of(
                () -> properties.setTenantId(" "),
                () -> properties.setIssuerUri(null),
                () -> properties.setClientId(""),
                () -> properties.setEnabled(false))) {
            setUp();
            broken.run();
            assertThatThrownBy(service::registerConfiguredOidcProvider)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(400);
        }
        verify(mapper, never()).insert(any(IdentityProvider.class));
    }

    @Test
    void 管理者以外は登録も参照もできない() {
        authenticate("営業");

        assertThatThrownBy(service::registerConfiguredOidcProvider)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(403);
        assertThatThrownBy(service::list).isInstanceOf(BusinessException.class);
        verify(mapper, never()).insert(any(IdentityProvider.class));
    }

    @Test
    void 一覧は設定tenantだけを返しtenant未設定なら空() {
        when(mapper.selectList(any())).thenReturn(List.of(new IdentityProvider()));
        assertThat(service.list()).hasSize(1);

        properties.setTenantId(null);
        assertThat(service.list()).isEmpty();
        verify(mapper).selectList(any());
    }

    private static void authenticate(String role) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("bootstrap-admin");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId("default");
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(user, authorities, "default"), null, authorities));
    }
}
