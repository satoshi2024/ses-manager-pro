package com.ses.service.impl;

import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Dashboard rolloff Top-N テスト (R3-016)")
class DashboardRolloffTopNTest {

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    @Mock
    private ContractMapper contractMapper;
    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProposalMapper proposalMapper;
    @Mock
    private WorkRecordMapper workRecordMapper;
    @Mock
    private EngineerSkillMapper engineerSkillMapper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private OrganizationScopeService organizationScopeService;
    @Spy
    private MonthlyRevenueCalcServiceImpl monthlyRevenueCalcService;
    @Spy
    private UtilizationCalcServiceImpl utilizationCalcService;

    @Test
    @DisplayName("retiringListは最大10件に制限される")
    void retiringListIsCappedAtTop10() {
        List<Contract> contracts = new ArrayList<>();
        List<Engineer> engineers = new ArrayList<>();
        for (long i = 1; i <= 15; i++) {
            Contract c = new Contract();
            c.setId(i);
            c.setEngineerId(i);
            c.setStatus("稼動中");
            c.setEndDate(LocalDate.now().plusDays(i));
            contracts.add(c);

            Engineer e = new Engineer();
            e.setId(i);
            e.setFullName("エンジニア " + i);
            e.setStatus("稼動中");
            engineers.add(e);
        }

        when(contractMapper.selectList(any())).thenReturn(contracts);
        when(engineerMapper.selectList(any())).thenReturn(engineers);
        when(engineerMapper.selectBatchIds(any())).thenReturn(engineers);

        DashboardSummaryDto summary = dashboardService.getSummary(3);

        // retiring list size should be capped at 10
        assertEquals(10, summary.getRetiring().size(), "retiringListは最大10件に制限されること");
    }
}
