package com.ses.service.servicedesk;

import com.ses.common.util.SecurityUtils;
import com.ses.entity.ServiceRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.RoleMenuMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.ServiceSlaEscalationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.service.security.ReportScopeContext;
import com.ses.service.security.CustomerScopeResolver;
import com.ses.service.servicedesk.impl.ServiceSlaMonitoringServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceSlaRecipientScopeTest {

    private final ServiceSlaClockMapper clockMapper = mock(ServiceSlaClockMapper.class);
    private final ServiceSlaEscalationMapper escalationMapper = mock(ServiceSlaEscalationMapper.class);
    private final ServiceRequestMapper requestMapper = mock(ServiceRequestMapper.class);
    private final ContractMapper contractMapper = mock(ContractMapper.class);
    private final CustomerMapper customerMapper = mock(CustomerMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
    private final CustomerScopeResolver scopeResolver = mock(CustomerScopeResolver.class);
    private ServiceSlaMonitoringServiceImpl service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        service = new ServiceSlaMonitoringServiceImpl(clockMapper, escalationMapper, requestMapper,
                contractMapper, customerMapper, userMapper, notificationService,
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), roleMenuMapper);
        ReflectionTestUtils.setField(service, "customerScopeResolver", scopeResolver);
        when(roleMenuMapper.selectMenuKeysByRole(any())).thenReturn(List.of("service-desk"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void managerは自分の現在customerScopeに含まれる場合だけrecipientになる() {
        SysUser manager = user(21L, "マネージャー");
        ServiceRequest request = request(manager.getId());
        when(userMapper.selectByIdAndTenant(manager.getId(), "default")).thenReturn(manager);
        // 候補recipient本人のprincipalが渡され、scheduler実行主体のscopeを使わないことを確認する。
        when(scopeResolver.resolve(any(LocalDate.class))).thenAnswer(invocation ->
                SecurityUtils.currentUserId().equals(manager.getId()) ? Set.of(501L) : Set.of());

        assertThat(service.resolveNotificationRecipients(request)).containsExactly(manager.getId());

        when(scopeResolver.resolve(any(LocalDate.class))).thenReturn(Set.of(502L)); // マネージャー異動後のscope
        when(userMapper.selectActiveByRoleAndTenant("管理者", "default")).thenReturn(List.of());
        assertThat(service.resolveNotificationRecipients(request)).isEmpty();
        verify(notificationService, never()).publishToUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sales担当解除role変更とmenu撤回は候補をdenyし合法adminだけfallbackする() {
        SysUser sales = user(31L, "営業");
        ServiceRequest request = request(sales.getId());
        when(userMapper.selectByIdAndTenant(sales.getId(), "default")).thenReturn(sales);
        when(scopeResolver.resolve(any(LocalDate.class))).thenReturn(Set.of()); // 営業担当解除後のscope
        SysUser hr = user(32L, "HR");
        ServiceRequest hrRequest = request(hr.getId());
        when(userMapper.selectByIdAndTenant(hr.getId(), "default")).thenReturn(hr);
        SysUser engineer = user(33L, "要員");
        ServiceRequest engineerRequest = request(engineer.getId());
        when(userMapper.selectByIdAndTenant(engineer.getId(), "default")).thenReturn(engineer);
        when(userMapper.selectActiveByRoleAndTenant("管理者", "default")).thenReturn(List.of());

        assertThat(service.resolveNotificationRecipients(request)).isEmpty();
        assertThat(service.resolveNotificationRecipients(hrRequest)).isEmpty();
        assertThat(service.resolveNotificationRecipients(engineerRequest)).isEmpty();

        when(scopeResolver.resolve(any(LocalDate.class))).thenReturn(Set.of(501L));
        when(roleMenuMapper.selectMenuKeysByRole("営業")).thenReturn(List.of()); // service-desk menu撤回
        assertThat(service.resolveNotificationRecipients(request)).isEmpty();

        SysUser admin = user(41L, "管理者");
        when(userMapper.selectActiveByRoleAndTenant("管理者", "default")).thenReturn(List.of(admin));
        assertThat(service.resolveNotificationRecipients(request)).containsExactly(admin.getId());
    }

    @Test
    void candidate解決例外後もschedulerのsecurityRequestReportContextを元へ戻す() {
        SysUser manager = user(51L, "マネージャー");
        when(userMapper.selectByIdAndTenant(manager.getId(), "default")).thenReturn(manager);
        when(userMapper.selectActiveByRoleAndTenant("管理者", "default")).thenReturn(List.of());
        org.springframework.security.core.Authentication actor =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "scheduler", "n/a", List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(actor);
        ServletRequestAttributes requestAttributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(requestAttributes);
        ReportScopeSnapshot savedScope = new ReportScopeSnapshot("SYSTEM", 7L, false,
                List.of(91L), List.of(), "v1", "{}", "hash");
        doThrow(new IllegalStateException("scope query unavailable"))
                .when(scopeResolver).resolve(any(LocalDate.class));

        assertThat(ReportScopeContext.with(savedScope, () -> {
            assertThat(service.resolveNotificationRecipients(request(manager.getId()))).isEmpty();
            assertThat(ReportScopeContext.current()).isSameAs(savedScope);
            return ReportScopeContext.current();
        })).isSameAs(savedScope);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(actor);
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(requestAttributes);
        assertThat(ReportScopeContext.current()).isNull();
    }

    private ServiceRequest request(Long ownerId) {
        ServiceRequest request = new ServiceRequest();
        request.setId(901L);
        request.setTenantId("default");
        request.setCustomerId(501L);
        request.setOwnerUserId(ownerId);
        return request;
    }

    private SysUser user(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId("default");
        user.setUsername("user-" + id);
        user.setRole(role);
        user.setStatus(1);
        user.setDeletedFlag(0);
        return user;
    }
}
