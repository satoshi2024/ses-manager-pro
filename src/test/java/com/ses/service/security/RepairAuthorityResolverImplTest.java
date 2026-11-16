package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.MfaEnforcementFilter;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.BreakGlassIncident;
import com.ses.entity.SysUser;
import com.ses.mapper.BreakGlassIncidentMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.impl.RepairAuthorityResolverImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DOC-FINAL delta review P3-c: 修理権限の break-glass 判定は principal（DB の表記）を
 * {@link OidcSecurityProperties#isBreakGlassUsername} で比べる。名前の近い別口座（無点の ı）を break-glass の
 * {@code admin} と同一視しないことを、他の条件を全て満たした状態で固定する（独自の比較へ書き換えた場合の回帰を検出する）。
 */
class RepairAuthorityResolverImplTest {

    private static final String TENANT = "default";
    private final BreakGlassService breakGlassService = mock(BreakGlassService.class);
    private final BreakGlassIncidentMapper incidentMapper = mock(BreakGlassIncidentMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneOffset.UTC);
    private RepairAuthorityResolverImpl resolver;

    @BeforeEach
    void setUp() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId(TENANT);
        properties.setBreakGlassUsernames(Set.of("admin", "bg-second"));
        resolver = new RepairAuthorityResolverImpl(breakGlassService, incidentMapper, properties, clock);
        AccountingTenantContextHolder.setTenantId(TENANT);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ownership-repairs");
        request.getSession(true).setAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        request.getSession().setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(breakGlassService.validateBoundSession(any(), any())).thenReturn(BreakGlassService.BreakGlassDecision.ALLOW);
        when(incidentMapper.selectByIdAndTenant(TENANT, 10L)).thenReturn(activeIncident());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 設定どおりのbreak_glass口座は他の条件を満たせば修理権限を得る() {
        authenticate(user(1L, "admin"));

        RepairAuthorityResolver.RepairAuthority authority = resolver.requireAuthority();

        assertThat(authority.actorId()).isEqualTo(1L);
        assertThat(authority.incidentId()).isEqualTo(10L);
        assertThat(authority.approverId()).isEqualTo(12L);
    }

    @Test
    void 無点のıを含む別口座は他の条件を満たしても修理権限を得ない() {
        authenticate(user(9L, "admın"));

        assertThatThrownBy(resolver::requireAuthority)
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(breakGlassService, incidentMapper);
    }

    private static void authenticate(SysUser user) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        LoginUser principal = new LoginUser(user, authorities, TENANT);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
    }

    private static SysUser user(Long id, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole("管理者");
        user.setStatus(1);
        user.setDeletedFlag(0);
        user.setTenantId(TENANT);
        return user;
    }

    private BreakGlassIncident activeIncident() {
        BreakGlassIncident incident = new BreakGlassIncident();
        incident.setId(10L);
        incident.setTenantId(TENANT);
        incident.setStatus("ACTIVE");
        incident.setApprovedBy1(11L);
        incident.setApprovedBy2(12L);
        incident.setEnabledFrom(LocalDateTime.now(clock).minusMinutes(5));
        incident.setEnabledUntil(LocalDateTime.now(clock).plusMinutes(30));
        incident.setAllowedActions("dashboard.view,ownership-repair.manage");
        return incident;
    }
}
