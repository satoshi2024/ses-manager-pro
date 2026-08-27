package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.ReportTemplateVersion;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.report.impl.ReportRecipientPreviewServiceImpl;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportRecipientPreviewServiceImplTest {

    private ReportTemplateVersionMapper versionMapper;
    private SysUserMapper userMapper;
    private OrganizationScopeService scopeService;
    private ReportRecipientPreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        versionMapper = mock(ReportTemplateVersionMapper.class);
        userMapper = mock(SysUserMapper.class);
        scopeService = mock(OrganizationScopeService.class);
        service = new ReportRecipientPreviewServiceImpl(versionMapper, userMapper, scopeService, new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                loginUser(1L, "マネージャー"), "N/A", List.of(new SimpleGrantedAuthority("ROLE_マネージャー"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportScopeがrecipientScopeに包含される場合だけmanagerへ許可する() {
        ReportTemplateVersion version = publishedVersion();
        SysUser recipient = user(2L, "マネージャー");
        when(versionMapper.selectById(3L)).thenReturn(version);
        when(userMapper.selectList(any())).thenReturn(List.of(recipient));
        when(scopeService.allowedOrganizationIds(any(LocalDate.class)))
                .thenReturn(Set.of(10L), Set.of(10L, 11L));
        when(scopeService.allowedDirectUserIds(any(LocalDate.class)))
                .thenReturn(Set.of(), Set.of());

        var result = service.preview(3L, YearMonth.of(2026, 8));

        assertThat(result.getRecipients()).singleElement()
                .extracting(item -> item.getScopeDecision()).isEqualTo("ALLOW");
    }

    @Test
    void recipientScopeがreportScopeより狭い場合は配布対象にしない() {
        ReportTemplateVersion version = publishedVersion();
        SysUser recipient = user(2L, "マネージャー");
        when(versionMapper.selectById(3L)).thenReturn(version);
        when(userMapper.selectList(any())).thenReturn(List.of(recipient));
        when(scopeService.allowedOrganizationIds(any(LocalDate.class)))
                .thenReturn(Set.of(10L, 11L), Set.of(10L));
        when(scopeService.allowedDirectUserIds(any(LocalDate.class)))
                .thenReturn(Set.of(), Set.of());

        assertThatThrownBy(() -> service.preview(3L, YearMonth.of(2026, 8)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.managementReport.recipientScopeDenied");
    }

    private ReportTemplateVersion publishedVersion() {
        ReportTemplateVersion version = new ReportTemplateVersion();
        version.setId(3L);
        version.setStatus("PUBLISHED");
        version.setRecipientConfigJson("{\"roles\":[\"マネージャー\"]}");
        return version;
    }

    private SysUser user(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(1);
        user.setRole(role);
        return user;
    }

    private LoginUser loginUser(Long id, String role) {
        SysUser user = user(id, role);
        return new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
