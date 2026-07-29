package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
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
                new ProductionSecurityConfigurationValidator(oidc, mfa, session, mock(SysUserMapper.class));

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void prodは二つの有効な管理者と非既定鍵なら受理する() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setEnabled(true);
        oidc.setLocalLoginEnabled(false);
        oidc.setBreakGlassLoginEnabled(true);
        oidc.setBreakGlassUsernames(Set.of("admin-a", "admin-b"));
        MfaSecurityProperties mfa = new MfaSecurityProperties();
        mfa.setEncryptionKey("01234567890123456789012345678901");
        PersistentSessionProperties session = new PersistentSessionProperties();
        session.setAllowUntrackedSessions(false);
        session.setHashKey("abcdefghijklmnopqrstuvwxyz123456");

        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectByUsername(anyString())).thenAnswer(invocation -> {
            SysUser user = new SysUser();
            user.setUsername(invocation.getArgument(0));
            user.setRole("管理者");
            user.setStatus(1);
            return user;
        });
        ProductionSecurityConfigurationValidator validator =
                new ProductionSecurityConfigurationValidator(oidc, mfa, session, mapper);

        assertDoesNotThrow(validator::validate);
    }
}
