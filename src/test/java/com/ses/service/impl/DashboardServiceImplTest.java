package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

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

    // 共通口径サービスは純粋ロジックのため実体をSpyとして注入する(Dashboardのチャート/KPIが実ロジックで計算される)
    @org.mockito.Spy
    private MonthlyRevenueCalcServiceImpl monthlyRevenueCalcService = new MonthlyRevenueCalcServiceImpl();

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    private Contract createContract(Long id, String contractNo, Integer sellingPrice, Integer costPrice, LocalDate startDate) {
        Contract c = new Contract();
        c.setId(id);
        c.setContractNo(contractNo);
        c.setEngineerId(id);
        c.setProjectId(id);
        c.setSellingPrice(sellingPrice != null ? java.math.BigDecimal.valueOf(sellingPrice) : null);
        c.setCostPrice(costPrice != null ? java.math.BigDecimal.valueOf(costPrice) : null);
        c.setStartDate(startDate);
        return c;
    }

    private Engineer createEngineer(Long id, String name) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setFullName(name);
        return e;
    }

    private Project createProject(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setProjectName(name);
        return p;
    }

    @Test
    void testGetProfitAnalysis_Success() {
        Contract c1 = createContract(1L, "C001", 1000000, 600000, LocalDate.of(2026, 7, 1));
        Engineer e1 = createEngineer(1L, "Test Engineer 1");
        Project p1 = createProject(1L, "Test Project 1");

        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(c1));
        when(engineerMapper.selectById(1L)).thenReturn(e1);
        when(projectMapper.selectById(1L)).thenReturn(p1);

        List<ContractProfitDto> result = dashboardService.getProfitAnalysis();

        assertEquals(1, result.size());
        ContractProfitDto dto = result.get(0);
        assertEquals("C001", dto.getContractNo());
        assertEquals("Test Engineer 1", dto.getEngineerName());
        assertEquals("Test Project 1", dto.getProjectName());
        assertEquals(1000000, dto.getSellingPrice());
        assertEquals(600000, dto.getCostPrice());
        assertEquals(400000, dto.getGrossProfitAmount());
        assertEquals("40.0%", dto.getGrossProfitRate());
    }

    @Test
    void testGetProfitAnalysis_ZeroSellingPrice() {
        Contract c1 = createContract(1L, "C001", 0, 600000, LocalDate.of(2026, 7, 1));
        Engineer e1 = createEngineer(1L, "Test Engineer 1");
        Project p1 = createProject(1L, "Test Project 1");

        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(c1));
        when(engineerMapper.selectById(1L)).thenReturn(e1);
        when(projectMapper.selectById(1L)).thenReturn(p1);

        List<ContractProfitDto> result = dashboardService.getProfitAnalysis();

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getSellingPrice());
        assertEquals(-600000, result.get(0).getGrossProfitAmount());
        assertEquals("N/A", result.get(0).getGrossProfitRate());
    }

    @Test
    void testGetProfitAnalysis_SortDesc() {
        Contract c1 = createContract(1L, "C001", 1000000, 600000, LocalDate.of(2026, 7, 1)); // Older
        Contract c2 = createContract(2L, "C002", 800000, 500000, LocalDate.of(2026, 8, 1)); // Newer
        
        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(c1, c2));
        when(engineerMapper.selectById(any())).thenReturn(createEngineer(1L, "Eng"));
        when(projectMapper.selectById(any())).thenReturn(createProject(1L, "Proj"));

        List<ContractProfitDto> result = dashboardService.getProfitAnalysis();

        assertEquals(2, result.size());
        assertEquals("C002", result.get(0).getContractNo()); // Newer first
        assertEquals("C001", result.get(1).getContractNo());
    }

    @Test
    void testGetProfitAnalysis_Empty() {
        when(contractMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        List<ContractProfitDto> result = dashboardService.getProfitAnalysis();
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetSummary_WithYear() {
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = dashboardService.getSummary(2026);
        assertNotNull(result);
        assertEquals(12, result.getCharts().getRevenue().getLabels().size());
        assertEquals("4月", result.getCharts().getRevenue().getLabels().get(0));
        assertEquals("3月", result.getCharts().getRevenue().getLabels().get(11));
    }

    @Test
    void testGetSummary_WithoutYear() {
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = dashboardService.getSummary(null);
        assertNotNull(result);
        assertEquals(6, result.getCharts().getRevenue().getLabels().size());
    }

    @Test
    void testGetSummary_WithActuals() {
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
        // 共通口径は契約単位フォールバック: 実績は該当契約に紐づく必要があるため、稼動契約を用意する
        java.time.YearMonth actualMonth = java.time.YearMonth.now().minusMonths(5);
        Contract c1 = new Contract();
        c1.setId(1L);
        c1.setStatus("稼動中");
        c1.setStartDate(actualMonth.atDay(1));
        c1.setEndDate(null);
        when(contractMapper.selectList(any())).thenReturn(List.of(c1));

        WorkRecord wr = new WorkRecord();
        wr.setContractId(1L);
        wr.setWorkMonth(actualMonth.toString());
        wr.setBillingAmount(java.math.BigDecimal.valueOf(1000000));
        wr.setPaymentAmount(java.math.BigDecimal.valueOf(600000));
        when(workRecordMapper.selectList(any())).thenReturn(List.of(wr));

        var result = dashboardService.getSummary(null);
        assertNotNull(result);
        assertEquals(6, result.getCharts().getRevenue().getLabels().size());
        assertEquals(1000000, result.getCharts().getRevenue().getSales().get(0));
        assertEquals(400000, result.getCharts().getRevenue().getProfit().get(0));
        assertTrue(result.getCharts().getRevenue().getIsActual().get(0));
    }

    @Test
    void testGetSummary_退場予定の抽出は終了日の下限も条件に含む() {
        // 退場予定リストは「本日〜30日以内に終了する」契約が対象であり、
        // 既に終了済み（end_date < 本日）の契約を含めてはならない。
        Engineer e2 = new Engineer();
        e2.setId(2L);
        e2.setFullName("Target Engineer");
        e2.setStatus("稼動中");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e2));

        Contract c1 = new Contract();
        c1.setId(1L);
        c1.setEngineerId(1L);
        c1.setStatus("稼動中");
        c1.setEndDate(LocalDate.now().minusDays(1)); // 既に終了
        
        Contract c2 = new Contract();
        c2.setId(2L);
        c2.setEngineerId(2L);
        c2.setStatus("稼動中");
        c2.setEndDate(LocalDate.now().plusDays(10)); // 対象
        
        when(contractMapper.selectList(any())).thenReturn(List.of(c1, c2));

        when(engineerMapper.selectBatchIds(any())).thenReturn(List.of(e2));

        var result = dashboardService.getSummary(null);

        assertEquals(1, result.getRetiring().size(), "退場予定リストに既に終了した契約が含まれていないこと");
        assertEquals(2L, result.getRetiring().get(0).getId());
    }

    @Test
    void testGetSummary_退場予定チャートは退場予定リストと同じ契約終了日ベースで集計する() {
        Engineer engineer = createEngineer(1L, "Retiring Engineer");
        engineer.setStatus("稼動中");
        when(engineerMapper.selectList(any())).thenReturn(List.of(engineer));

        Contract contract = new Contract();
        contract.setId(1L);
        contract.setEngineerId(1L);
        contract.setStatus("稼動中");
        contract.setEndDate(LocalDate.now().plusDays(10));
        when(contractMapper.selectList(any())).thenReturn(List.of(contract));
        when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(engineerMapper.selectBatchIds(any())).thenReturn(List.of(engineer));
        when(engineerSkillMapper.selectTopSkillCandidates(any())).thenReturn(Collections.emptyList());
        when(proposalMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = dashboardService.getSummary(null);

        assertEquals(List.of(0, 0, 1, 0), result.getCharts().getStatus().getData());
        assertEquals(1, result.getRetiring().size());
        assertEquals(1L, result.getRetiring().get(0).getId());
    }

    @Test
    void testGetSummary_退場予定は要員単位で重複せず他ステータスにも重複計上しない() {
        Engineer engineer = createEngineer(1L, "Retiring Engineer");
        engineer.setStatus("Bench");
        when(engineerMapper.selectList(any())).thenReturn(List.of(engineer));

        Contract later = createContract(1L, "C001", 100, 50, LocalDate.now());
        later.setStatus("稼動中");
        later.setEndDate(LocalDate.now().plusDays(20));
        Contract earlier = createContract(2L, "C002", 100, 50, LocalDate.now());
        earlier.setEngineerId(1L);
        earlier.setStatus("稼動中");
        earlier.setEndDate(LocalDate.now().plusDays(10));
        when(contractMapper.selectList(any())).thenReturn(List.of(later, earlier));
        when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(engineerMapper.selectBatchIds(any())).thenReturn(List.of(engineer));
        when(engineerSkillMapper.selectTopSkillCandidates(any())).thenReturn(Collections.emptyList());
        when(proposalMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = dashboardService.getSummary(null);

        assertEquals(List.of(0, 0, 1, 0), result.getCharts().getStatus().getData());
        assertEquals(1, result.getRetiring().size());
        assertEquals(LocalDate.now().plusDays(10).toString().replace('-', '/'), result.getRetiring().get(0).getDate());
    }

    @Test
    void testGetSummary_トレンド計算() {
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
        // 当月・前月ともに実績が該当契約に紐づく前提(共通口径は契約単位)
        Contract c1 = new Contract();
        c1.setId(1L);
        c1.setStatus("稼動中");
        c1.setStartDate(java.time.YearMonth.now().minusMonths(2).atDay(1));
        c1.setEndDate(null);
        when(contractMapper.selectList(any())).thenReturn(List.of(c1));

        WorkRecord currentWr = new WorkRecord();
        currentWr.setContractId(1L);
        currentWr.setWorkMonth(java.time.YearMonth.now().toString());
        currentWr.setBillingAmount(java.math.BigDecimal.valueOf(1200000));
        currentWr.setPaymentAmount(java.math.BigDecimal.valueOf(800000));

        WorkRecord prevWr = new WorkRecord();
        prevWr.setContractId(1L);
        prevWr.setWorkMonth(java.time.YearMonth.now().minusMonths(1).toString());
        prevWr.setBillingAmount(java.math.BigDecimal.valueOf(1000000));
        prevWr.setPaymentAmount(java.math.BigDecimal.valueOf(700000));

        when(workRecordMapper.selectList(any())).thenReturn(List.of(currentWr, prevWr));

        var result = dashboardService.getSummary(null);
        assertNotNull(result);
        assertEquals("+20.0%", result.getKpi().getRevenueTrend()); // (120-100)/100 = 20%
        assertEquals("+33.3%", result.getKpi().getProfitTrend()); // (40-30)/30 = 33.33...
        assertNull(result.getKpi().getUtilizationTrend());
    }

    @Test
    void testGetSummary_代表スキル選定と提案数() {
        Engineer e = createEngineer(1L, "Test Engineer");
        e.setStatus("稼動中");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e));
        
        Contract c = createContract(1L, "C001", 1000000, 600000, LocalDate.now().minusDays(10));
        c.setEndDate(LocalDate.now().plusDays(10));
        c.setStatus("稼動中");
        when(contractMapper.selectList(any())).thenReturn(List.of(c));
        
        when(engineerMapper.selectBatchIds(any())).thenReturn(List.of(e));

        EngineerSkillDetailDto skill = new EngineerSkillDetailDto();
        skill.setEngineerId(1L);
        skill.setSkillName("Java");
        when(engineerSkillMapper.selectTopSkillCandidates(any())).thenReturn(List.of(skill));

        com.ses.entity.Proposal p1 = new com.ses.entity.Proposal();
        p1.setEngineerId(1L);
        com.ses.entity.Proposal p2 = new com.ses.entity.Proposal();
        p2.setEngineerId(1L);
        when(proposalMapper.selectList(any())).thenReturn(List.of(p1, p2));

        var result = dashboardService.getSummary(null);
        assertNotNull(result.getRetiring());
        assertEquals(1, result.getRetiring().size());
        assertEquals("Java", result.getRetiring().get(0).getSkill());
        assertEquals(2, result.getRetiring().get(0).getProposals());
    }
}
