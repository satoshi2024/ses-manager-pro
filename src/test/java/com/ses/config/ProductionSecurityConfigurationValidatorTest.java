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

    // ---------------------------------------------------------------- NF07 W-5（§3.1.3）

    @Test
    void 全体baselineを持つgroupが全てprivacyの拒否行を持てば起動を受理する() {
        com.ses.mapper.PermissionGroupMapper permissionGroupMapper = mock(com.ses.mapper.PermissionGroupMapper.class);
        when(permissionGroupMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(permissionGroupMapper.selectWildcardGroupKeysMissingPrivacyDeny("default")).thenReturn(java.util.List.of());

        assertDoesNotThrow(validProductionValidator(permissionGroupMapper)::validate);
        org.mockito.Mockito.verify(permissionGroupMapper).selectWildcardGroupKeysMissingPrivacyDeny("default");
    }

    @Test
    void 全体baselineを持ちprivacyの拒否行を欠くgroupがあれば起動を止めgroup_keyだけを出す() {
        com.ses.mapper.PermissionGroupMapper permissionGroupMapper = mock(com.ses.mapper.PermissionGroupMapper.class);
        when(permissionGroupMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(permissionGroupMapper.selectWildcardGroupKeysMissingPrivacyDeny("default"))
                .thenReturn(java.util.List.of("role-admin", "copied-admin"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                validProductionValidator(permissionGroupMapper)::validate);
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("privacy.*"), error.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("copied-admin,role-admin"),
                error.getMessage());
    }

    @Test
    void privacyの拒否不変条件を照会できなければ起動を止める() {
        com.ses.mapper.PermissionGroupMapper permissionGroupMapper = mock(com.ses.mapper.PermissionGroupMapper.class);
        when(permissionGroupMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(permissionGroupMapper.selectWildcardGroupKeysMissingPrivacyDeny("default"))
                .thenThrow(new IllegalStateException("db unavailable"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                validProductionValidator(permissionGroupMapper)::validate);
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("privacy deny不変条件を確認できません"),
                error.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("db unavailable"), error.getMessage());
    }

    // ---------------------------------------------------------------- 管理者の継続性guard（RB-ADMIN-GUARD）

    @Test
    void 継続性guard向けの判定はvalidateと同じ照会でOIDC管理者とbreak_glassを数える() {
        com.ses.mapper.PermissionGroupMapper permissionGroupMapper = mock(com.ses.mapper.PermissionGroupMapper.class);
        when(permissionGroupMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        when(permissionGroupMapper.selectWildcardGroupKeysMissingPrivacyDeny("default")).thenReturn(java.util.List.of());

        ProductionSecurityConfigurationValidator satisfied = administratorValidator(permissionGroupMapper,
                java.util.List.of("ops-a", "ops-b", "ops-c"), 1);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(
                AdministratorConditionCheck.evaluated(AdministratorConditionCheck.Condition.OIDC_ADMINISTRATORS, 3, 3, true),
                AdministratorConditionCheck.evaluated(
                        AdministratorConditionCheck.Condition.BREAK_GLASS_ADMINISTRATORS, 2, 2, true)),
                satisfied.checkAdministratorConditions());
        assertDoesNotThrow(satisfied::validate);

        // OIDC管理者が2名（break-glass usernameは数えない）・admin-b のMFAが未登録: 起動時検査と同じく両方を満たさない。
        ProductionSecurityConfigurationValidator broken = administratorValidator(permissionGroupMapper,
                java.util.List.of("ops-a", "ops-b", "admin-a"), 0);
        java.util.List<AdministratorConditionCheck> checks = broken.checkAdministratorConditions();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(
                AdministratorConditionCheck.evaluated(AdministratorConditionCheck.Condition.OIDC_ADMINISTRATORS, 2, 3, false),
                AdministratorConditionCheck.evaluated(
                        AdministratorConditionCheck.Condition.BREAK_GLASS_ADMINISTRATORS, 1, 2, false)), checks);
        IllegalStateException error = assertThrows(IllegalStateException.class, broken::validate);
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("現在2名"), error.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("MFA enrollment完了済み"),
                error.getMessage());

        // bootstrap中は validate と同じく、どちらも判定しない（照会もしない）。
        ProductionBootstrapMode bootstrap = mock(ProductionBootstrapMode.class);
        when(bootstrap.isActive()).thenReturn(true);
        broken.setProductionBootstrapMode(bootstrap);
        checks = broken.checkAdministratorConditions();
        org.junit.jupiter.api.Assertions.assertTrue(checks.stream().noneMatch(AdministratorConditionCheck::evaluated),
                checks::toString);
        org.mockito.Mockito.verify(bootstrap, org.mockito.Mockito.never()).completePendingReopen();
    }

    /** OIDC管理者の照会結果と admin-b のMFA登録数だけを変えた本番設定（admin-a は常にMFA登録済み）。 */
    private static ProductionSecurityConfigurationValidator administratorValidator(
            com.ses.mapper.PermissionGroupMapper permissionGroupMapper, java.util.List<String> oidcAdministrators,
            int adminBEnrolled) {
        ProductionSecurityConfigurationValidator validator = validProductionValidator(permissionGroupMapper);
        UserMfaMapper userMfaMapper = mock(UserMfaMapper.class);
        when(userMfaMapper.countEnrolled("default", 1L)).thenReturn(1);
        when(userMfaMapper.countEnrolled("default", 2L)).thenReturn(adminBEnrolled);
        org.springframework.test.util.ReflectionTestUtils.setField(validator, "userMfaMapper", userMfaMapper);
        com.ses.mapper.UserExternalIdentityMapper externalIdentityMapper =
                mock(com.ses.mapper.UserExternalIdentityMapper.class);
        when(externalIdentityMapper.selectApprovedActiveUsernamesByRole("default", 7L, "管理者"))
                .thenReturn(oidcAdministrators);
        validator.setUserExternalIdentityMapper(externalIdentityMapper);
        return validator;
    }

    /** NF07以外の検査が全て通る本番設定（privacy の検査結果だけで起動可否が決まる）。 */
    private static ProductionSecurityConfigurationValidator validProductionValidator(
            com.ses.mapper.PermissionGroupMapper permissionGroupMapper) {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setEnabled(true);
        oidc.setLocalLoginEnabled(false);
        oidc.setBreakGlassLoginEnabled(true);
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
        ProductionSecurityConfigurationValidator validator =
                new ProductionSecurityConfigurationValidator(oidc, mfa, session, mapper, userMfaMapper,
                        permissionGroupMapper, new AiConfig(), new com.ses.common.security.OutboundUrlGuard());
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
        return validator;
    }
}
