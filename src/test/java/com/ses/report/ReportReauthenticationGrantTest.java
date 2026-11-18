package com.ses.report;

import com.ses.service.report.ReportReauthenticationGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportReauthenticationGrantTest {

    private MutableClock clock;
    private ReportReauthenticationGrant grants;
    private MockHttpServletRequest sessionA;
    private MockHttpServletRequest sessionB;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
        grants = new ReportReauthenticationGrant(clock);
        sessionA = request(new MockHttpSession());
        sessionB = request(new MockHttpSession());
        authentication = authenticated("1", "ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void grantは発行したsessionとtenant_user_delivery_scopeにだけ有効() {
        assertGrantValid();
        assertThatThrownBy(() -> grants.require(sessionB, "tenant-a", 1L, 7L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
        assertGrantValid();
        assertThatThrownBy(() -> grants.require(sessionA, "tenant-b", 1L, 7L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
        assertGrantValid();
        assertThatThrownBy(() -> grants.require(sessionA, "tenant-a", 2L, 7L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
        assertGrantValid();
        assertThatThrownBy(() -> grants.require(sessionA, "tenant-a", 1L, 8L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
        assertGrantValid();
        assertThatThrownBy(() -> grants.require(sessionA, "tenant-a", 1L, 7L, "scope-b"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
    }

    @Test
    void expiry_sessionRotation_logoutとauthentication変更を無効にする() {
        grants.issue(sessionA, "tenant-a", 1L, 7L, "scope-a");
        clock.advance(Duration.ofMinutes(10));
        assertDenied(sessionA);

        grants.issue(sessionA, "tenant-a", 1L, 7L, "scope-a");
        ((MockHttpSession) sessionA.getSession(false)).changeSessionId();
        assertDenied(sessionA);

        grants.issue(sessionA, "tenant-a", 1L, 7L, "scope-a");
        SecurityContextHolder.getContext().setAuthentication(authenticated("2", "ROLE_管理者"));
        assertDenied(sessionA);

        SecurityContextHolder.getContext().setAuthentication(authentication);
        grants.issue(sessionA, "tenant-a", 1L, 7L, "scope-a");
        ((MockHttpSession) sessionA.getSession(false)).invalidate();
        assertDenied(request(null));
    }

    @Test
    void role変更後は同user_sessionでも再認証が必要() {
        grants.issue(sessionA, "tenant-a", 1L, 7L, "scope-a");
        SecurityContextHolder.getContext().setAuthentication(authenticated("1", "ROLE_マネージャー"));
        assertDenied(sessionA);
    }

    @Test
    void anonymousAuthenticationとsessionのないrequestでは発行も使用も拒否() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatThrownBy(() -> grants.issue(sessionA, "tenant-a", 1L, 7L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MockHttpServletRequest noSession = request(null);
        assertThatThrownBy(() -> grants.issue(noSession, "tenant-a", 1L, 7L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
        assertThatThrownBy(() -> grants.require(noSession, "tenant-a", 1L, 7L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
    }

    private void assertDenied(MockHttpServletRequest request) {
        assertThatThrownBy(() -> grants.require(request, "tenant-a", 1L, 7L, "scope-a"))
                .hasMessageContaining("error.managementReport.reauthenticationRequired");
    }

    private void assertGrantValid() {
        grants.issue(sessionA, "tenant-a", 1L, 7L, "scope-a");
        grants.require(sessionA, "tenant-a", 1L, 7L, "scope-a");
    }

    private MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (session != null) request.setSession(session);
        return request;
    }

    private UsernamePasswordAuthenticationToken authenticated(String user, String role) {
        return new UsernamePasswordAuthenticationToken(user, "N/A", List.of(new SimpleGrantedAuthority(role)));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
