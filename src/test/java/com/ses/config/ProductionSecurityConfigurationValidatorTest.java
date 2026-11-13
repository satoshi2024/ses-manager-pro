package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserMfaMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionSecurityConfigurationValidatorTest {

    @Test
    void prodの開発用既定値は拒否する() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        MfaSecurityProperties mfa = new MfaSecurityProperties();
        PersistentSessionProperties session = new PersistentSessionProperties();

        ProductionSecurityConfigurationValidator validator =
                new ProductionSecurityConfigurationValidator(oidc, mfa, session,
                        mock(SysUserMapper.class), mock(UserMfaMapper.class), mock(com.ses.mapper.PermissionGroupMapper.class),
                        new AiConfig(), new com.ses.common.security.OutboundUrlGuard());

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void prodは二つの有効な管理者と非既定鍵なら受理する() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setEnabled(true);
        oidc.setLocalLoginEnabled(false);
        oidc.setBreakGlassLoginEnabled(true);
        // prodではtenant-idが常に必須（review P2-B）
        oidc.setTenantId("default");
        oidc.setBreakGlassUsernames(Set.of("admin-a", "admin-b"));
        oidc.setIssuerUri("https://idp.example/tenant/v2.0");
        oidc.setAuthorizationUri("https://idp.example/authorize");
        oidc.setTokenUri("https://idp.example/token");
        oidc.setJwkSetUri("https://idp.example/jwks");
        oidc.setUserInfoUri("https://idp.example/userinfo");
        oidc.setClientId("client");
        oidc.setClientSecret("secret");
        MfaSecurityProperties mfa = new MfaSecurityProperties();
        mfa.setEncryptionKey("01234567890123456789012345678901");
        PersistentSessionProperties session = new PersistentSessionProperties();
        session.setAllowUntrackedSessions(false);
        session.setHashKey("abcdefghijklmnopqrstuvwxyz123456");

        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectByUsername(anyString())).thenAnswer(invocation -> {
            SysUser user = new SysUser();
            user.setUsername(invocation.getArgument(0));
            user.setId("admin-a".equals(invocation.getArgument(0)) ? 1L : 2L);
            user.setRole("管理者");
            user.setStatus(1);
            return user;
        });
        UserMfaMapper userMfaMapper = mock(UserMfaMapper.class);
        when(userMfaMapper.countEnrolled(org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        com.ses.mapper.PermissionGroupMapper permissionGroupMapper = mock(com.ses.mapper.PermissionGroupMapper.class);
        when(permissionGroupMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        AiConfig aiConfig = new AiConfig();
        com.ses.common.security.OutboundUrlGuard outboundUrlGuard = new com.ses.common.security.OutboundUrlGuard();
        ProductionSecurityConfigurationValidator validator =
                new ProductionSecurityConfigurationValidator(oidc, mfa, session, mapper, userMfaMapper, permissionGroupMapper,
                        aiConfig, outboundUrlGuard);
        // break-glass以外にOIDCでログインできる管理者3名（起票1名・承認2名）と、初回bootstrapの完了marker
        com.ses.entity.IdentityProvider provider = new com.ses.entity.IdentityProvider();
        provider.setId(7L);
        provider.setProviderType("OIDC");
        com.ses.mapper.IdentityProviderMapper identityProviderMapper = mock(com.ses.mapper.IdentityProviderMapper.class);
        when(identityProviderMapper.selectEnabledByTenantAndIssuer(org.mockito.ArgumentMatchers.eq("default"),
                anyString())).thenReturn(provider);
        com.ses.mapper.UserExternalIdentityMapper externalIdentityMapper =
                mock(com.ses.mapper.UserExternalIdentityMapper.class);
        when(externalIdentityMapper.selectApprovedActiveUsernamesByRole("default", 7L, "管理者"))
                .thenReturn(java.util.List.of("ops-a", "ops-b", "ops-c"));
        validator.setIdentityProviderMapper(identityProviderMapper);
        validator.setUserExternalIdentityMapper(externalIdentityMapper);
        validator.setBootstrapCompletionLatch(mock(ProductionBootstrapCompletionLatch.class));

        assertDoesNotThrow(validator::validate);

        oidc.setTokenUri("http://idp.example/token");
        assertThrows(IllegalStateException.class, validator::validate);
        oidc.setTokenUri("https://idp.example/token");
        oidc.setIssuerUri("https://user@idp.example/tenant");
        assertThrows(IllegalStateException.class, validator::validate);
        oidc.setIssuerUri("https://idp.example/tenant");

        // ai.provider=gemini かつ不正なapi-urlは本番起動検証で拒否する。
        aiConfig.setProvider("gemini");
        aiConfig.setApiUrl("https://evil.example.com/v1beta/models/x:generateContent");
        assertThrows(IllegalStateException.class, validator::validate);
        aiConfig.setApiUrl("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent");
        assertDoesNotThrow(validator::validate);
    }
}
