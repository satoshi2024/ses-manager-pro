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
                        mock(SysUserMapper.class), mock(UserMfaMapper.class), mock(com.ses.mapper.PermissionGroupMapper.class));

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void prodは二つの有効な管理者と非既定鍵なら受理する() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setEnabled(true);
        oidc.setLocalLoginEnabled(false);
        oidc.setBreakGlassLoginEnabled(true);
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
        ProductionSecurityConfigurationValidator validator =
                new ProductionSecurityConfigurationValidator(oidc, mfa, session, mapper, userMfaMapper, permissionGroupMapper);

        assertDoesNotThrow(validator::validate);

        oidc.setTokenUri("http://idp.example/token");
        assertThrows(IllegalStateException.class, validator::validate);
        oidc.setTokenUri("https://idp.example/token");
        oidc.setIssuerUri("https://user@idp.example/tenant");
        assertThrows(IllegalStateException.class, validator::validate);
    }
}
