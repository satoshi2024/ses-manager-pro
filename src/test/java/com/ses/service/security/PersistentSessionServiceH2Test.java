package com.ses.service.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.entity.UserSession;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserSessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** R3-001: H2上でsession上限と期限・失効行の扱いを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.session.max-concurrent-sessions=5")
@Transactional
class PersistentSessionServiceH2Test {

    @Autowired
    private PersistentSessionService persistentSessionService;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private UserSessionMapper userSessionMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 同一ユーザーで6sessionを発行するとactiveは5件になる() {
        SysUser user = insertUser("r3_h2_six", 1);
        authenticate(user);

        for (int i = 0; i < 6; i++) {
            persistentSessionService.register(new MockHttpServletRequest(), currentAuthentication());
        }

        List<UserSession> active = userSessionMapper.selectActiveByUser(
                "default", user.getId(), LocalDateTime.now());
        assertEquals(5, active.size());
        long revoked = userSessionMapper.selectList(new LambdaQueryWrapper<UserSession>()
                        .eq(UserSession::getUserId, user.getId()))
                .stream().filter(s -> "MAX_CONCURRENT".equals(s.getRevokeReason())).count();
        assertEquals(1, revoked);
    }

    @Test
    void 期限切れと失効済みsessionは上限へ含めない() {
        SysUser user = insertUser("r3_h2_expired", 1);
        authenticate(user);
        LocalDateTime now = LocalDateTime.now();
        insertHistoricalSession(user.getId(), "a".repeat(64), now.minusHours(2), now.minusHours(1), null);
        insertHistoricalSession(user.getId(), "b".repeat(64), now.minusMinutes(10), now.plusHours(1), now.minusMinutes(5));

        for (int i = 0; i < 5; i++) {
            persistentSessionService.register(new MockHttpServletRequest(), currentAuthentication());
        }

        assertEquals(5, userSessionMapper.selectActiveByUser("default", user.getId(), LocalDateTime.now()).size());
        long maxConcurrentRevokes = userSessionMapper.selectList(new LambdaQueryWrapper<UserSession>()
                        .eq(UserSession::getUserId, user.getId()))
                .stream().filter(s -> "MAX_CONCURRENT".equals(s.getRevokeReason())).count();
        assertEquals(0, maxConcurrentRevokes);
    }

    @Test
    void 無効ユーザーにはsessionを発行しない() {
        SysUser user = insertUser("r3_h2_disabled", 0);
        authenticate(user);

        assertThrows(BusinessException.class,
                () -> persistentSessionService.register(new MockHttpServletRequest(), currentAuthentication()));
        assertEquals(0, userSessionMapper.selectList(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getUserId, user.getId())).size());
    }

    private SysUser insertUser(String username, int status) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("test-password");
        user.setRealName("H2 session検証");
        user.setRole("営業");
        user.setStatus(status);
        sysUserMapper.insert(user);
        return user;
    }

    private void authenticate(SysUser user) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(new LoginUser(user, List.of()), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private UsernamePasswordAuthenticationToken currentAuthentication() {
        return (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
    }

    private void insertHistoricalSession(Long userId, String hash, LocalDateTime idleExpiresAt,
                                         LocalDateTime expiresAt, LocalDateTime revokedAt) {
        UserSession session = new UserSession();
        session.setTenantId("default");
        session.setSessionIdHash(hash);
        session.setUserId(userId);
        session.setIssuedAt(LocalDateTime.now().minusHours(3));
        session.setLastSeenAt(LocalDateTime.now().minusHours(2));
        session.setIdleExpiresAt(idleExpiresAt);
        session.setExpiresAt(expiresAt);
        session.setRevokedAt(revokedAt);
        session.setRevokeReason(revokedAt == null ? null : "LOGOUT");
        userSessionMapper.insert(session);
    }
}
