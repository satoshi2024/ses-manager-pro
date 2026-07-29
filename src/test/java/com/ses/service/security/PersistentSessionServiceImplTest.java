package com.ses.service.security;

import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.PersistentSessionProperties;
import com.ses.entity.SysUser;
import com.ses.entity.UserSession;
import com.ses.mapper.UserSessionMapper;
import com.ses.service.security.impl.PersistentSessionServiceImpl;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentSessionServiceImplTest {

    @Mock
    private UserSessionMapper userSessionMapper;

    private PersistentSessionServiceImpl service;
    private UsernamePasswordAuthenticationToken authentication;
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        PersistentSessionProperties properties = new PersistentSessionProperties();
        properties.setHashKey("test-session-key");
        properties.setMaxConcurrentSessions(2);
        properties.setIdleTimeoutMinutes(30);
        properties.setMaxLifetimeHours(12);
        OidcSecurityProperties oidcProperties = new OidcSecurityProperties();
        oidcProperties.setTenantId("tenant-a");
        service = new PersistentSessionServiceImpl(userSessionMapper, properties, oidcProperties, clock);

        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("admin");
        authentication = new UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of()), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sessionIDを平文保存せず登録属性を設定する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userSessionMapper.selectBySessionHash(anyString(), anyString())).thenReturn(null);
        when(userSessionMapper.selectActiveByUser(anyString(), eq(7L), any())).thenReturn(List.of());

        service.register(request, authentication);

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionMapper).insert(captor.capture());
        HttpSession session = request.getSession(false);
        assertNotEquals(session.getId(), captor.getValue().getSessionIdHash());
        assertTrue(Boolean.TRUE.equals(session.getAttribute(PersistentSessionService.TRACKED_ATTRIBUTE)));
        assertNotEquals(null, session.getAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE));
    }

    @Test
    void 永続sessionが期限切れならfailClosedする() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession(true);
        session.setAttribute(PersistentSessionService.TRACKED_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE, "hashed-session");
        UserSession expired = new UserSession();
        expired.setId(10L);
        expired.setUserId(7L);
        expired.setTenantId("tenant-a");
        expired.setSessionIdHash("hashed-session");
        expired.setIdleExpiresAt(LocalDateTime.of(2025, 12, 31, 23, 59));
        expired.setExpiresAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        when(userSessionMapper.selectBySessionHash("tenant-a", "hashed-session")).thenReturn(expired);

        assertFalse(service.validateAndTouch(request, authentication));
    }

    @Test
    void 同時session上限を超えた場合は最古を失効する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UserSession oldest = active(1L, LocalDateTime.of(2025, 12, 1, 0, 0));
        UserSession newest = active(2L, LocalDateTime.of(2025, 12, 31, 0, 0));
        when(userSessionMapper.selectBySessionHash(anyString(), anyString())).thenReturn(null);
        when(userSessionMapper.selectActiveByUser(anyString(), eq(7L), any()))
                .thenReturn(List.of(newest, oldest));

        service.register(request, authentication);

        verify(userSessionMapper).revoke(1L, LocalDateTime.of(2026, 1, 1, 0, 0), "MAX_CONCURRENT");
    }

    private UserSession active(Long id, LocalDateTime issuedAt) {
        UserSession session = new UserSession();
        session.setId(id);
        session.setUserId(7L);
        session.setTenantId("tenant-a");
        session.setSessionIdHash("session-" + id);
        session.setIssuedAt(issuedAt);
        session.setIdleExpiresAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        session.setExpiresAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return session;
    }
}
