package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportScheduleCreateRequest;
import com.ses.entity.ReportSchedule;
import com.ses.entity.ReportTemplateVersion;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.service.report.impl.ReportScheduleServiceImpl;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportScheduleServiceImplTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void managerScheduleStoresCreationScopeAndStartsDisabled() {
        ReportScheduleMapper scheduleMapper = mock(ReportScheduleMapper.class);
        ReportTemplateVersionMapper versionMapper = mock(ReportTemplateVersionMapper.class);
        OrganizationScopeService scopeService = mock(OrganizationScopeService.class);
        ReportScheduleServiceImpl service = new ReportScheduleServiceImpl(scheduleMapper, versionMapper,
                scopeService, new ObjectMapper());
        authenticate(7L, "マネージャー");
        when(versionMapper.selectById(3L)).thenReturn(publishedVersion());
        when(scopeService.allowedOrganizationIds(any())).thenReturn(Set.of(10L));
        when(scopeService.allowedDirectUserIds(any())).thenReturn(Set.of(20L));
        when(scopeService.allowedEngineerIds(any())).thenReturn(Set.of(30L));
        when(scopeService.allowedContractIds(any())).thenReturn(Set.of(40L));
        when(scopeService.allowedInvoiceIds(any())).thenReturn(Set.of(50L));
        doAnswer(invocation -> {
            ReportSchedule schedule = invocation.getArgument(0);
            schedule.setId(11L);
            return 1;
        }).when(scheduleMapper).insert(any(ReportSchedule.class));

        ReportScheduleCreateRequest request = new ReportScheduleCreateRequest();
        request.setTemplateVersionId(3L);
        request.setCronExpression("0 0 9 1 * *");
        request.setNextRunAt(LocalDateTime.of(2026, 9, 1, 9, 0));

        ReportSchedule schedule = service.create(request);

        assertThat(schedule.getEnabled()).isZero();
        assertThat(schedule.getTimezoneId()).isEqualTo("Asia/Tokyo");
        assertThat(schedule.getScopeOwnerId()).isEqualTo(7L);
        assertThat(schedule.getOrganizationScopeJson()).contains("\"invoiceIds\":[50]");
        assertThat(schedule.getScopeHash()).hasSize(64);
        verify(scheduleMapper).insert(any(ReportSchedule.class));
    }

    @Test
    void invalidCronIsRejectedBeforePersistingSchedule() {
        ReportScheduleMapper scheduleMapper = mock(ReportScheduleMapper.class);
        ReportTemplateVersionMapper versionMapper = mock(ReportTemplateVersionMapper.class);
        OrganizationScopeService scopeService = mock(OrganizationScopeService.class);
        ReportScheduleServiceImpl service = new ReportScheduleServiceImpl(scheduleMapper, versionMapper,
                scopeService, new ObjectMapper());
        authenticate(1L, "管理者");
        ReportScheduleCreateRequest request = new ReportScheduleCreateRequest();
        request.setTemplateVersionId(3L);
        request.setCronExpression("not-a-cron");

        assertThatThrownBy(() -> service.create(request))
                .hasMessageContaining("error.managementReport.scheduleInvalid");
    }

    @Test
    void missingNextRunUsesNextCronOccurrenceInTokyo() {
        ReportScheduleMapper scheduleMapper = mock(ReportScheduleMapper.class);
        ReportTemplateVersionMapper versionMapper = mock(ReportTemplateVersionMapper.class);
        OrganizationScopeService scopeService = mock(OrganizationScopeService.class);
        ReportScheduleServiceImpl service = new ReportScheduleServiceImpl(scheduleMapper, versionMapper,
                scopeService, new ObjectMapper());
        authenticate(1L, "管理者");
        when(versionMapper.selectById(3L)).thenReturn(publishedVersion());
        doAnswer(invocation -> 1).when(scheduleMapper).insert(any(ReportSchedule.class));

        ReportScheduleCreateRequest request = new ReportScheduleCreateRequest();
        request.setTemplateVersionId(3L);
        request.setCronExpression("0 0 9 1 * *");

        ReportSchedule schedule = service.create(request);

        assertThat(schedule.getNextRunAt().getDayOfMonth()).isEqualTo(1);
        assertThat(schedule.getNextRunAt().getHour()).isEqualTo(9);
        assertThat(schedule.getNextRunAt().getMinute()).isZero();
    }

    private void authenticate(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private ReportTemplateVersion publishedVersion() {
        ReportTemplateVersion version = new ReportTemplateVersion();
        version.setId(3L);
        version.setStatus("PUBLISHED");
        return version;
    }
}
