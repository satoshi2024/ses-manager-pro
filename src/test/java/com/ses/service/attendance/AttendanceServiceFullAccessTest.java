package com.ses.service.attendance;

import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.impl.AttendanceServiceImpl;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R2-P1-04のfull-access空集合sentinelを管理一覧の実呼出しで固定する。 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceFullAccessTest {

    @Mock
    private AttendanceMonthMapper attendanceMonthMapper;
    @Mock
    private EmployeeAttendanceMapper employeeAttendanceMapper;
    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private EngineerAccountLinkService engineerAccountLinkService;
    @Mock
    private OrganizationScopeService organizationScopeService;
    @Mock
    private AttendanceCalculator attendanceCalculator;
    @Mock
    private AttendanceScopeResolver attendanceScopeResolver;
    @Mock
    private ApprovalEngineService approvalEngineService;
    @Mock
    private WorkCalendarDayMapper workCalendarDayMapper;

    @InjectMocks
    private AttendanceServiceImpl attendanceService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fullAccessでは空のallowedEngineerIdsを可視0件へ変換しない() {
        authenticateManager();
        when(organizationScopeService.hasFullAccess()).thenReturn(true);

        var overview = attendanceService.management("2026-08");

        assertEquals("2026-08", overview.getMonth());
        verify(organizationScopeService).hasFullAccess();
        verify(organizationScopeService, never()).allowedEngineerIds(org.mockito.ArgumentMatchers.any());
    }

    private void authenticateManager() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("93001", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_マネージャー"))));
    }
}
