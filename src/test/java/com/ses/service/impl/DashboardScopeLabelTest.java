package com.ses.service.impl;

import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Dashboard scope表記テスト (R3-017)")
class DashboardScopeLabelTest {

    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private ContractMapper contractMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private WorkRecordMapper workRecordMapper;
    @Mock
    private EngineerSkillMapper engineerSkillMapper;
    @Mock
    private ProposalMapper proposalMapper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private OrganizationScopeService organizationScopeService;

    @Spy
    private MonthlyRevenueCalcServiceImpl monthlyRevenueCalcService = new MonthlyRevenueCalcServiceImpl();
    @Spy
    private UtilizationCalcServiceImpl utilizationCalcService = new UtilizationCalcServiceImpl();

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    @BeforeEach
    void setUp() {
        lenient().when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("全社アクセス可能時はscopeType=COMPANY, scopeDisplayName=全社を返す")
    void fullAccessReturnsCompanyScope() {
        lenient().when(dataScopeService.isScoped()).thenReturn(false);
        lenient().when(organizationScopeService.hasFullAccess()).thenReturn(true);

        DashboardSummaryDto summary = dashboardService.getSummary(null);

        assertEquals("COMPANY", summary.getKpi().getScopeType());
        assertEquals("全社", summary.getKpi().getScopeDisplayName());
    }

    @Test
    @DisplayName("スコープ制限時はscopeType=LIMITED, scopeDisplayName=対象範囲を返す")
    void scopedAccessReturnsLimitedScope() {
        lenient().when(dataScopeService.isScoped()).thenReturn(true);

        DashboardSummaryDto summary = dashboardService.getSummary(null);

        assertEquals("LIMITED", summary.getKpi().getScopeType());
        assertEquals("対象範囲", summary.getKpi().getScopeDisplayName());
    }
}
