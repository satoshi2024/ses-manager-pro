package com.ses.service.pwa;

import com.ses.dto.pwa.PwaRestoreConfirmRequest;
import com.ses.dto.pwa.PwaRestoreReauthenticateRequest;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.pwa.PwaRestoreIdentityService.CurrentIdentity;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import com.ses.config.PwaRestoreSessionListener;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.WebUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PwaRestoreAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T01:00:00Z");
    private final MutableClock clock = new MutableClock(NOW);
    private final PwaRestoreIdentityService identityService = mock(PwaRestoreIdentityService.class);
    private final PwaUserContextService userContextService = mock(PwaUserContextService.class);
    private final PwaRestoreFailureRecorder failureRecorder = mock(PwaRestoreFailureRecorder.class);
    private final PwaRestoreSessionRegistry registry = new PwaRestoreSessionRegistry(clock);
    private final PwaRestoreAuthorizationService service = new PwaRestoreAuthorizationService(
            identityService, userContextService, failureRecorder, registry,
            NoOpPasswordEncoder.getInstance(), clock);
    private MockHttpSession session;
    private UsernamePasswordAuthenticationToken authentication;
    private CurrentIdentity identity;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        session.setMaxInactiveInterval(300);
        SysUser user = new SysUser();
        user.setId(7L); user.setUsername("user-a"); user.setTenantId("tenant-a");
        user.setRole("要員"); user.setStatus(1); user.setPassword(null);
        authentication = UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_要員")), "tenant-a"), "",
                List.of(new SimpleGrantedAuthority("ROLE_要員")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        identity = new CurrentIdentity(7L, "user-a", "tenant-a", "要員", "pw", null,
                44L, 9L, Instant.parse("2026-09-01T00:00:00Z"), false);
        when(identityService.load(7L, "tenant-a")).thenReturn(identity);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void proofはdigestだけをsessionへ保存しconfirmで単回消費される() {
        PwaRestoreReauthenticateRequest reauth = reauth(nonce(1), "scope-v2");
        var response = service.reauthenticate(reauth, session, authentication);
        assertThat(response.proof()).hasSize(43);
        assertThat(response.toString()).doesNotContain(response.proof());
        assertThat(reauth.toString()).doesNotContain("pw");
        Object state = session.getAttribute(PwaRestoreAuthorizationService.SESSION_STATE_ATTRIBUTE);
        Object proofs = ReflectionTestUtils.getField(state, "proofs");
        assertThat(proofs.toString()).doesNotContain(response.proof(), "scope-v2");

        PwaRestoreConfirmRequest confirm = confirm(response.proof(), reauth.getDocumentNonce(), "scope-v2");
        var permit = service.confirm(confirm, session, authentication);
        assertThat(permit.userScope()).isEqualTo("scope-v2");
        assertThat(permit.toString()).doesNotContain(permit.userScope(), permit.documentNonce());
        assertThat(permit.serverTime()).isEqualTo(NOW);
        assertThat(permit.permitExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThatThrownBy(() -> service.confirm(confirm, session, authentication))
                .hasMessage("error.pwa.restore.rejected");
    }

    @Test
    void 正しいpasswordでも失効scope拒否し失敗加算はwrongPasswordだけ() {
        when(userContextService.assertRestoreScope("legacy-scope", "tenant-a", 7L, 9L, 44L,
                identity.linkedAt())).thenThrow(new com.ses.common.exception.BusinessException(403,
                "error.pwa.restore.rejected"));
        assertThatThrownBy(() -> service.reauthenticate(reauth(nonce(2), "legacy-scope"), session, authentication))
                .hasMessage("error.pwa.restore.rejected");
        verify(failureRecorder, never()).record(any(), any());
    }

    @Test
    void wrongPasswordは独立failureRecorderへcurrentDB主体だけを渡す() {
        PwaRestoreReauthenticateRequest bad = reauth(nonce(3), "scope-v2");
        bad.setPassword("incorrect");
        assertThatThrownBy(() -> service.reauthenticate(bad, session, authentication))
                .hasMessage("error.pwa.restore.rejected");
        verify(failureRecorder).record(7L, "tenant-a");
    }

    @Test
    void nonce再発行は旧proofを失効させ未消費proofは4件まで() {
        String sameNonce = nonce(4);
        var first = service.reauthenticate(reauth(sameNonce, "scope-v2"), session, authentication);
        var replacement = service.reauthenticate(reauth(sameNonce, "scope-v2"), session, authentication);
        assertThatThrownBy(() -> service.confirm(confirm(first.proof(), sameNonce, "scope-v2"), session, authentication))
                .hasMessage("error.pwa.restore.rejected");
        service.reauthenticate(reauth(nonce(5), "scope-v2"), session, authentication);
        service.reauthenticate(reauth(nonce(6), "scope-v2"), session, authentication);
        service.reauthenticate(reauth(nonce(7), "scope-v2"), session, authentication);
        assertThatThrownBy(() -> service.reauthenticate(reauth(nonce(8), "scope-v2"), session, authentication))
                .hasMessage("error.pwa.restore.rejected");
        assertThat(replacement.expiresAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void password変更link差替lock発生とsession破棄後のconsumeを拒否する() {
        var proof = service.reauthenticate(reauth(nonce(9), "scope-v2"), session, authentication);
        when(identityService.load(7L, "tenant-a")).thenReturn(new CurrentIdentity(7L, "user-a", "tenant-a",
                "要員", "changed", null, 44L, 9L, identity.linkedAt(), false));
        assertThatThrownBy(() -> service.confirm(confirm(proof.proof(), nonce(9), "scope-v2"), session, authentication))
                .hasMessage("error.pwa.restore.rejected");

        when(identityService.load(7L, "tenant-a")).thenReturn(identity);
        var relinkProof = service.reauthenticate(reauth(nonce(10), "scope-v2"), session, authentication);
        when(identityService.load(7L, "tenant-a")).thenReturn(new CurrentIdentity(7L, "user-a", "tenant-a",
                "要員", "pw", null, 45L, 9L, identity.linkedAt().plusSeconds(1), false));
        assertThatThrownBy(() -> service.confirm(confirm(relinkProof.proof(), nonce(10), "scope-v2"), session, authentication))
                .hasMessage("error.pwa.restore.rejected");

        when(identityService.load(7L, "tenant-a")).thenReturn(identity);
        var second = service.reauthenticate(reauth(nonce(12), "scope-v2"), session, authentication);
        registry.destroyed(session.getId());
        assertThatThrownBy(() -> service.confirm(confirm(second.proof(), nonce(12), "scope-v2"), session, authentication))
                .hasMessage("error.pwa.restore.rejected");
    }

    @Test
    void 同じproofの並行consumeは片方だけ成功する() throws Exception {
        var proof = service.reauthenticate(reauth(nonce(11), "scope-v2"), session, authentication);
        var request = confirm(proof.proof(), nonce(11), "scope-v2");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> consumeAfter(start, request));
            var b = pool.submit(() -> consumeAfter(start, request));
            start.countDown();
            int successes = (a.get(3, TimeUnit.SECONDS) ? 1 : 0) + (b.get(3, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void proofは120秒ちょうどで失効する() {
        var proof = service.reauthenticate(reauth(nonce(13), "scope-v2"), session, authentication);
        clock.set(NOW.plusSeconds(120));
        assertThatThrownBy(() -> service.confirm(confirm(proof.proof(), nonce(13), "scope-v2"),
                session, authentication)).hasMessage("error.pwa.restore.rejected");
    }

    @Test
    void nonceScope認証主体またはconsume時lockが変わるとproofを拒否する() {
        var wrongNonce = service.reauthenticate(reauth(nonce(15), "scope-v2"), session, authentication);
        assertThatThrownBy(() -> service.confirm(confirm(wrongNonce.proof(), nonce(16), "scope-v2"),
                session, authentication)).hasMessage("error.pwa.restore.rejected");

        var wrongScope = service.reauthenticate(reauth(nonce(17), "scope-v2"), session, authentication);
        assertThatThrownBy(() -> service.confirm(confirm(wrongScope.proof(), nonce(17), "scope-other"),
                session, authentication)).hasMessage("error.pwa.restore.rejected");

        var changedAuthProof = service.reauthenticate(reauth(nonce(18), "scope-v2"), session, authentication);
        var replacementAuth = UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(userForAuthentication(), List.of(new SimpleGrantedAuthority("ROLE_要員")), "tenant-a"), "",
                List.of(new SimpleGrantedAuthority("ROLE_要員")));
        SecurityContext changedContext = SecurityContextHolder.createEmptyContext();
        changedContext.setAuthentication(replacementAuth);
        SecurityContextHolder.setContext(changedContext);
        assertThatThrownBy(() -> service.confirm(confirm(changedAuthProof.proof(), nonce(18), "scope-v2"),
                session, replacementAuth)).hasMessage("error.pwa.restore.rejected");

        SecurityContextHolder.setContext(contextFor(authentication));
        var lockProof = service.reauthenticate(reauth(nonce(19), "scope-v2"), session, authentication);
        when(identityService.load(7L, "tenant-a")).thenReturn(new CurrentIdentity(7L, "user-a", "tenant-a",
                "要員", "pw", java.time.LocalDateTime.ofInstant(NOW.plusSeconds(1800), ZoneOffset.UTC),
                44L, 9L, identity.linkedAt(), true));
        assertThatThrownBy(() -> service.confirm(confirm(lockProof.proof(), nonce(19), "scope-v2"),
                session, authentication)).hasMessage("error.pwa.restore.rejected");
    }

    @Test
    void logoutListenerはconfirmと共通mutexを待ってsession世代を失効させる() throws Exception {
        var proof = service.reauthenticate(reauth(nonce(14), "scope-v2"), session, authentication);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> {
                synchronized (WebUtils.getSessionMutex(session)) {
                    acquired.countDown();
                    release.await();
                }
                return null;
            });
            assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
            var logout = pool.submit(() -> new PwaRestoreSessionListener(registry)
                    .sessionDestroyed(new HttpSessionEvent(session)));
            assertThat(registry.isDestroyed(session.getId())).isFalse();
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
            logout.get(2, TimeUnit.SECONDS);
            assertThat(registry.isDestroyed(session.getId())).isTrue();
            assertThatThrownBy(() -> service.confirm(confirm(proof.proof(), nonce(14), "scope-v2"),
                    session, authentication)).hasMessage("error.pwa.restore.rejected");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private boolean consumeAfter(CountDownLatch start, PwaRestoreConfirmRequest request) throws Exception {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        start.await();
        try {
            synchronized (WebUtils.getSessionMutex(session)) {
                service.confirm(request, session, authentication);
            }
            return true;
        } catch (RuntimeException rejected) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static PwaRestoreReauthenticateRequest reauth(String nonce, String scope) {
        var request = new PwaRestoreReauthenticateRequest();
        request.setPassword("pw"); request.setDocumentNonce(nonce); request.setUserScope(scope);
        return request;
    }

    private static PwaRestoreConfirmRequest confirm(String proof, String nonce, String scope) {
        var request = new PwaRestoreConfirmRequest();
        request.setProof(proof); request.setDocumentNonce(nonce); request.setUserScope(scope);
        return request;
    }

    private static SecurityContext contextFor(UsernamePasswordAuthenticationToken auth) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }

    private static SysUser userForAuthentication() {
        SysUser user = new SysUser();
        user.setId(7L); user.setUsername("user-a"); user.setTenantId("tenant-a"); user.setRole("要員");
        user.setStatus(1);
        return user;
    }

    private static String nonce(int seed) {
        byte[] bytes = new byte[16]; bytes[0] = (byte) seed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant value) { instant = value; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
