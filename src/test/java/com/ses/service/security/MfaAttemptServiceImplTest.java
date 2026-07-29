package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
import com.ses.config.MfaSecurityProperties;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.PersistentSessionProperties;
import com.ses.entity.MfaAttemptGuard;
import com.ses.mapper.MfaAttemptGuardMapper;
import com.ses.service.security.impl.MfaAttemptServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaAttemptServiceImplTest {

    private static final String HASH_KEY = "test-session-hash-key-0123456789012345";
    private final MfaAttemptGuardMapper mapper = mock(MfaAttemptGuardMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private MfaAttemptServiceImpl service;

    @BeforeEach
    void setUp() {
        request.setRemoteAddr("192.0.2.10");
        request.getSession(true);
        MfaSecurityProperties mfa = new MfaSecurityProperties();
        mfa.setMaxAttempts(5);
        mfa.setAttemptWindowSeconds(300);
        mfa.setLockSeconds(900);
        PersistentSessionProperties session = new PersistentSessionProperties();
        session.setHashKey(HASH_KEY);
        service = new MfaAttemptServiceImpl(mapper, mfa, session, new OidcSecurityProperties(), clock);
    }

    @Test
    void 五回目の失敗でaccountSessionSourceの各scopeをlockする() {
        MfaAttemptGuard account = guard(10L);
        MfaAttemptGuard session = guard(11L);
        MfaAttemptGuard source = guard(12L);
        when(mapper.selectForUpdate(eq("default"), eq(7L), anyString(), anyString()))
                .thenReturn(account, session, source);
        when(mapper.updateById(account)).thenReturn(1);
        when(mapper.updateById(session)).thenReturn(1);
        when(mapper.updateById(source)).thenReturn(1);

        service.recordFailure(7L, request);

        for (MfaAttemptGuard guard : new MfaAttemptGuard[]{account, session, source}) {
            assertEquals(5, guard.getFailedCount());
            assertNotNull(guard.getLockedUntil());
        }
        verify(mapper).updateById(account);
        verify(mapper).updateById(session);
        verify(mapper).updateById(source);
        verify(mapper).selectForUpdate("default", 7L, hash("ANY_SESSION"), hash("ANY_SOURCE"));
        verify(mapper).selectForUpdate("default", 7L, hash(request.getSession().getId()), hash("ANY_SOURCE"));
        verify(mapper).selectForUpdate("default", 7L, hash("ANY_SESSION"), hash("192.0.2.10"));
    }

    @Test
    void lock期間中の試行は検証前に拒否する() {
        MfaAttemptGuard guard = new MfaAttemptGuard();
        guard.setLockedUntil(LocalDateTime.now(clock).plusMinutes(1));
        when(mapper.selectForUpdate(eq("default"), eq(7L), anyString(), anyString())).thenReturn(guard);

        assertThrows(BusinessException.class, () -> service.assertAllowed(7L, request));
    }

    @Test
    void sessionと接続元を変更してもaccountScopeのlockは回避できない() {
        MfaAttemptGuard account = new MfaAttemptGuard();
        account.setLockedUntil(LocalDateTime.now(clock).plusMinutes(1));
        when(mapper.selectForUpdate("default", 7L, hash("ANY_SESSION"), hash("ANY_SOURCE")))
                .thenReturn(account);
        MockHttpServletRequest another = new MockHttpServletRequest();
        another.setRemoteAddr("198.51.100.20");
        another.getSession(true);

        assertThrows(BusinessException.class, () -> service.assertAllowed(7L, another));
    }

    private MfaAttemptGuard guard(long id) {
        MfaAttemptGuard guard = new MfaAttemptGuard();
        guard.setId(id);
        guard.setFailedCount(4);
        guard.setWindowStartedAt(LocalDateTime.now(clock).minusSeconds(30));
        return guard;
    }

    private String hash(String value) {
        return SecurityHashUtil.sha256(HASH_KEY + ":mfa-attempt:" + value);
    }
}
