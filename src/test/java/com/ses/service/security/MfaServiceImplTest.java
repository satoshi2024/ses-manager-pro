package com.ses.service.security;

import com.ses.config.MfaSecurityProperties;
import com.ses.config.OidcSecurityProperties;
import com.ses.common.util.TotpUtil;
import com.ses.dto.security.MfaSetupResponse;
import com.ses.entity.MfaRecoveryCode;
import com.ses.entity.SysUser;
import com.ses.entity.UserMfa;
import com.ses.mapper.MfaRecoveryCodeMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserMfaMapper;
import com.ses.service.security.impl.MfaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaServiceImplTest {

    @Mock
    private UserMfaMapper userMfaMapper;
    @Mock
    private MfaRecoveryCodeMapper recoveryCodeMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private PersistentSessionService persistentSessionService;

    private MfaServiceImpl service;
    private UserMfa mfa;
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MfaSecurityProperties mfaProperties = new MfaSecurityProperties();
        mfaProperties.setEncryptionKey("test-only-mfa-key");
        mfaProperties.setRecoveryCodeCount(3);
        OidcSecurityProperties oidcProperties = new OidcSecurityProperties();
        oidcProperties.setTenantId("tenant-a");
        oidcProperties.setBreakGlassUsernames(Set.of("admin"));
        service = new MfaServiceImpl(userMfaMapper, recoveryCodeMapper, sysUserMapper,
                mfaProperties, oidcProperties, persistentSessionService, clock);
        mfa = new UserMfa();
        mfa.setId(10L);
        mfa.setTenantId("tenant-a");
        mfa.setUserId(1L);
    }

    @Test
    void breakGlassだけMFA必須である() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of());
        UsernamePasswordAuthenticationToken ordinary =
                new UsernamePasswordAuthenticationToken("user", "n/a", List.of());

        assertTrue(service.isRequired(authentication));
        assertFalse(service.isRequired(ordinary));
    }

    @Test
    void setup_enableでsecretを暗号化保存しrecoveryCodeを一度だけ発行する() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setStatus(1);
        when(userMfaMapper.selectOne(any())).thenReturn(mfa);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(userMfaMapper.advanceLastUsedStep(10L, 1_700_000_000L / 30L)).thenReturn(1);
        when(recoveryCodeMapper.insert(any(MfaRecoveryCode.class))).thenReturn(1);

        MfaSetupResponse setup = service.setup(1L);
        assertNotNull(setup.secret());
        assertNotNull(setup.otpauthUri());
        assertTrue(setup.recoveryCodes().isEmpty());

        mfa.setEncryptedTotpSecret(captureEncryptedSecret());
        MfaSetupResponse enabled = service.enable(1L,
                TotpUtil.code(setup.secret(), 1_700_000_000L / 30L, 6));

        assertTrue(enabled.enabled());
        assertNotNull(enabled.recoveryCodes());
        assertTrue(enabled.recoveryCodes().size() == 3);
        verify(userMfaMapper).prepareSetup(10L, mfa.getEncryptedTotpSecret(), "v1");
    }

    @Test
    void 同じTOTPstepのreplayはatomic更新で拒否される() {
        String secret = prepareSecret();
        mfa.setEnabledAt(java.time.LocalDateTime.now(clock));
        when(userMfaMapper.advanceLastUsedStep(10L, 1_700_000_000L / 30L)).thenReturn(0);

        assertFalse(service.verify(1L, TotpUtil.code(secret, 1_700_000_000L / 30L, 6)));
    }

    @Test
    void recoveryCodeは使用済みになると再利用できない() {
        prepareSecret();
        mfa.setEnabledAt(java.time.LocalDateTime.now(clock));
        MfaRecoveryCode recoveryCode = new MfaRecoveryCode();
        recoveryCode.setId(22L);
        when(recoveryCodeMapper.selectAvailable(anyString(), anyLong(), anyString()))
                .thenReturn(recoveryCode).thenReturn(null);
        when(recoveryCodeMapper.markUsed(anyLong(), any())).thenReturn(1);

        assertTrue(service.verify(1L, "ABCD-1234"));
        assertFalse(service.verify(1L, "ABCD-1234"));
    }

    private String prepareSecret() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setStatus(1);
        when(userMfaMapper.selectOne(any())).thenReturn(mfa);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        MfaSetupResponse setup = service.setup(1L);
        mfa.setEncryptedTotpSecret(captureEncryptedSecret());
        return setup.secret();
    }

    private String captureEncryptedSecret() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(userMfaMapper).prepareSetup(org.mockito.ArgumentMatchers.eq(10L), captor.capture(),
                org.mockito.ArgumentMatchers.eq("v1"));
        return captor.getValue();
    }
}
