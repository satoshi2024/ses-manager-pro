package com.ses.service.impl;

import com.ses.entity.SysUser;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.config.LoginUser;
import com.ses.service.EngineerSalesService;
import com.ses.service.SystemConfigService;
import com.ses.service.UtilizationCalcService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NF08: canonicalサービスはbind済みsnapshotを正本とし、認可サービスを再実行しない。 */
@ExtendWith(MockitoExtension.class)
class CopilotCanonicalServiceSnapshotTest {
    @Mock ContractMapper contractMapper;
    @Mock EngineerMapper engineerMapper;
    @Mock ProjectMapper projectMapper;
    @Mock WorkRecordMapper workRecordMapper;
    @Mock EngineerSkillMapper engineerSkillMapper;
    @Mock ProposalMapper proposalMapper;
    @Mock MonthlyAccountingDimensionMapper dimensionMapper;
    @Mock ManagementBudgetMapper budgetMapper;
    @Mock MonthlyRevenueCalcService monthlyRevenueCalcService;
    @Mock SystemConfigService systemConfigService;
    @Mock DataScopeService dataScopeService;
    @Mock OrganizationScopeService organizationScopeService;
    @Mock OrganizationService organizationService;
    @Mock EngineerSalesService engineerSalesService;
    @Mock UtilizationCalcService utilizationCalcService;
    @Mock com.ses.mapper.CustomerMapper customerMapper;
    @Mock com.ses.mapper.SysUserMapper sysUserMapper;
    @Mock java.time.Clock clock;

    @InjectMocks DashboardServiceImpl dashboardService;
    @InjectMocks UtilizationForecastServiceImpl utilizationForecastService;
    @InjectMocks ManagementAccountingServiceImpl managementAccountingService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bind後に権限サービスが変わっても三つのcanonicalサービスは再計算しない() {
        loginAsAdmin();
        CopilotExecutionContext context = context();

        when(contractMapper.selectList(any())).thenReturn(List.of());
        when(contractMapper.selectAccountingContractsFiltered(any(), any(), anyBoolean(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(engineerMapper.selectList(any())).thenReturn(List.of());
        when(workRecordMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectList(any())).thenReturn(List.of());
        when(dimensionMapper.selectCount(any())).thenReturn(0L);
        when(budgetMapper.selectList(any())).thenReturn(List.of());
        when(organizationService.namesByIds(anySet())).thenReturn(Map.of());

        // query開始後に権限が変化したとしても、canonical pathはこのサービス群を再度呼ばない。
        lenient().when(dataScopeService.isScoped()).thenReturn(true);
        lenient().when(organizationScopeService.hasFullAccess()).thenReturn(false);
        clearInvocations(dataScopeService, organizationScopeService);
        dashboardService.getProfitAnalysis(context);
        utilizationForecastService.getForecast(context.asOfMonth(), 3, context);
        managementAccountingService.summary("2026-09", context);

        verifyNoInteractions(dataScopeService, organizationScopeService);
    }

    @Test
    void snapshotまたはcontextが無いcanonical呼び出しはfailClosedする() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThrows(BusinessException.class,
                        () -> dashboardService.getProfitAnalysis(null)),
                () -> assertThrows(BusinessException.class,
                        () -> utilizationForecastService.getForecast(null, 3, null)),
                () -> assertThrows(BusinessException.class,
                        () -> managementAccountingService.summary("2026-09", null)));
    }

    private CopilotExecutionContext context() {
        CopilotExecutionContext context = new CopilotExecutionContext(
                "tenant-a", 10L, Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshot(
                "tenant-a", 10L, LocalDate.of(2026, 9, 8), "COMPANY_WIDE",
                true, false, false,
                null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                "411584ab315cd7028c16ddf1be247ce0ce5f5e422626b65e30604678d9f71451");
        context.bindSnapshot(snapshot);
        return context;
    }

    private void loginAsAdmin() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("管理者");
        LoginUser loginUser = new LoginUser(user, List.of(), "tenant-a");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }
}
