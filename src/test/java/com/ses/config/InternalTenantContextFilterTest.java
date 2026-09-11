package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.IOException;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalTenantContextFilterTest {

    private OidcSecurityProperties oidcProperties;
    private AccountingTimezoneResolver timezoneResolver;
    private InternalTenantContextFilter filter;

    @BeforeEach
    void setUp() {
        oidcProperties = new OidcSecurityProperties();
        oidcProperties.setTenantId("tenant-a");
        timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.resolve("tenant-a")).thenReturn(ZoneId.of("Asia/Tokyo"));
        when(timezoneResolver.resolve("tenant-b")).thenReturn(ZoneId.of("Asia/Tokyo"));
        filter = new InternalTenantContextFilter(oidcProperties, timezoneResolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 認証主体のtenantだけをbindしrequest入力を認可根拠にせず完了後にclearする() throws Exception {
        MockHttpServletRequest request = request("/api/service-desk/requests");
        request.setParameter("tenantId", "tenant-b");
        request.addHeader("X-Tenant-Id", "tenant-b");
        SecurityContextHolder.getContext().setAuthentication(authentication(user("tenant-a")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertEquals("tenant-a", AccountingTenantContextHolder.requireTenantContext());
            assertEquals("tenant-a", AccountingTenantContextHolder.getExplicitTenantId());
        });

        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
        assertEquals("Asia/Tokyo", AccountingTenantContextHolder.getZoneId().getId());
    }

    @Test
    void thread再利用時も前requestのtenantを持ち越さない() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication(user("tenant-a")));
        filter.doFilter(request("/api/one"), new MockHttpServletResponse(), (req, res) ->
                assertEquals("tenant-a", AccountingTenantContextHolder.requireTenantContext()));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());

        SecurityContextHolder.getContext().setAuthentication(authentication(user("tenant-b")));
        filter.doFilter(request("/api/two"), new MockHttpServletResponse(), (req, res) ->
                assertEquals("tenant-b", AccountingTenantContextHolder.requireTenantContext()));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void requestExceptionClearsTenantContext() {
        SecurityContextHolder.getContext().setAuthentication(authentication(user("tenant-a")));

        assertThrows(IOException.class, () -> filter.doFilter(request("/api/error"),
                new MockHttpServletResponse(), throwingChain()));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void tenant欠落principalはfailClosedする() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication(user(null)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/service-desk/requests"), response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains(InternalTenantContextFilter.TENANT_CONTEXT_REQUIRED));
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void OIDCtenant不一致はfailClosedする() throws Exception {
        OidcUser delegate = mock(OidcUser.class);
        OidcLoginUser principal = new OidcLoginUser(user("tenant-b"), delegate,
                List.of(new SimpleGrantedAuthority("ROLE_HR")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/certification-learning-gap/masters/certifications"), response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains(InternalTenantContextFilter.OIDC_TENANT_MISMATCH));
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void 通常LoginUserでもOIDC設定tenantと不一致ならfailClosedする() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication(user("tenant-b")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/service-desk/requests"), response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains(InternalTenantContextFilter.OIDC_TENANT_MISMATCH));
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void 機械認証はmetrics以外の内部業務へ到達できない() throws Exception {
        Authentication machine = new UsernamePasswordAuthenticationToken(
                "metrics-scraper", null,
                List.of(new SimpleGrantedAuthority("ROLE_METRICS_SCRAPER")));
        SecurityContextHolder.getContext().setAuthentication(machine);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/notifications"), response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        MockHttpServletResponse metricsResponse = new MockHttpServletResponse();
        filter.doFilter(request("/actuator/prometheus"), metricsResponse, chain);
        assertEquals(200, metricsResponse.getStatus());
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticatedPrincipalにtenantが無くHolderだけあっても403() throws Exception {
        AccountingTenantContextHolder.setTenantId("holder-tenant");
        org.springframework.security.core.userdetails.User bare =
                new org.springframework.security.core.userdetails.User(
                        "bare", "pw", List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(bare, null, bare.getAuthorities()));
        // OIDC設定tenantがあってもprincipal束縛が無ければ補完しない
        oidcProperties.setTenantId("oidc-configured");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/service-desk/requests"), response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains(InternalTenantContextFilter.TENANT_CONTEXT_REQUIRED));
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals("holder-tenant", AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void authenticatedPrincipal無tenantでOIDC設定tenantがあっても自動補完しない() throws Exception {
        oidcProperties.setTenantId("oidc-only");
        org.springframework.security.core.userdetails.User bare =
                new org.springframework.security.core.userdetails.User(
                        "bare", "pw", List.of(new SimpleGrantedAuthority("ROLE_営業")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(bare, null, bare.getAuthorities()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/engineers"), response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains(InternalTenantContextFilter.TENANT_CONTEXT_REQUIRED));
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anonymousリクエストはtenantをbindせず通過する() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/login"), response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void 合法LoginUserとOIDCtenant一致は成功する() throws Exception {
        oidcProperties.setTenantId("tenant-a");
        SecurityContextHolder.getContext().setAuthentication(authentication(user("tenant-a")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/engineers"), response, (req, res) ->
                assertEquals("tenant-a", AccountingTenantContextHolder.requireTenantContext()));

        assertEquals(200, response.getStatus());
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void PortalLoginUserのtenantもprincipal束縛として成功する() throws Exception {
        oidcProperties.setTenantId(null);
        com.ses.portal.PortalLoginUser portal = com.ses.portal.PortalLoginUser.builder()
                .portalUserId(9L)
                .email("portal@example.com")
                .tenantId("tenant-portal")
                .userStatus("ACTIVE")
                .orgStatus("ACTIVE")
                .build();
        when(timezoneResolver.resolve("tenant-portal")).thenReturn(ZoneId.of("Asia/Tokyo"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(portal, null, portal.getAuthorities()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/portal/me"), response, (req, res) ->
                assertEquals("tenant-portal", AccountingTenantContextHolder.requireTenantContext()));

        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    private FilterChain throwingChain() {
        return (request, response) -> {
            assertEquals("tenant-a", AccountingTenantContextHolder.requireTenantContext());
            throw new IOException("request failed");
        };
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    private UsernamePasswordAuthenticationToken authentication(SysUser user) {
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private SysUser user(String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("tenant-user");
        user.setPassword("password");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId(tenantId);
        return user;
    }
}
