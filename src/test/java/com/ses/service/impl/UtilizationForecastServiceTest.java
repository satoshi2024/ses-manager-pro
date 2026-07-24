package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.dto.engineersales.EngineerPrimarySalesDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UtilizationForecastServiceTest {

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private EngineerSalesService engineerSalesService;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private DataScopeService dataScopeService;

    // 稼働判定は共通口径サービスの実ロジックを通す(ダッシュボードKPIと同一実装であることを担保する)
    @org.mockito.Spy
    private UtilizationCalcServiceImpl utilizationCalcService = new UtilizationCalcServiceImpl();

    @InjectMocks
    private UtilizationForecastServiceImpl forecastService;

    @BeforeEach
    void setUp() {
        lenient().when(systemConfigService.getString(eq("forecast.assume-renew"), eq("true"))).thenReturn("true");
    }

    private Engineer createEngineer(Long id, String name) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setFullName(name);
        e.setInitialName(name.substring(0, 1));
        e.setStatus(StatusConstants.ENGINEER_ACTIVE);
        return e;
    }

    private Contract createContract(Long id, Long engineerId, LocalDate startDate, LocalDate endDate, Integer autoRenew) {
        Contract c = new Contract();
        c.setId(id);
        c.setContractNo("C" + String.format("%03d", id));
        c.setEngineerId(engineerId);
        c.setProjectId(100L + id);
        c.setCustomerId(200L + id);
        c.setStartDate(startDate);
        c.setEndDate(endDate);
        c.setAutoRenew(autoRenew);
        c.setStatus(StatusConstants.CONTRACT_ACTIVE);
        return c;
    }

    @Test
    void testGetForecast_ThreeMonthsForecast_Success() {
        YearMonth currentYm = YearMonth.from(LocalDate.now());

        // 要員3名: E1 (無期限契約), E2 (今月末で終了・自動更新なし), E3 (来月開始)
        Engineer e1 = createEngineer(1L, "Eng One");
        Engineer e2 = createEngineer(2L, "Eng Two");
        Engineer e3 = createEngineer(3L, "Eng Three");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1, e2, e3));

        // E1: 稼働中 (無期限)
        Contract c1 = createContract(1L, 1L, currentYm.atDay(1), null, 1);
        // E2: 稼働中 (今月末終了, autoRenew=0 -> m1ロールオフ候補)
        Contract c2 = createContract(2L, 2L, currentYm.atDay(1), currentYm.atEndOfMonth(), 0);

        when(contractMapper.selectList(any())).thenReturn(List.of(c1, c2));

        // 担当営業マッピング
        EngineerPrimarySalesDto salesDto = new EngineerPrimarySalesDto(2L, 99L, "Sales Rep 1");
        when(engineerSalesService.mapPrimaryByEngineerIds(any())).thenReturn(Map.of(2L, salesDto));

        Project p = new Project();
        p.setId(102L);
        p.setProjectName("Project Alpha");
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(p));

        Customer cust = new Customer();
        cust.setId(202L);
        cust.setCompanyName("Customer Inc");
        when(customerMapper.selectBatchIds(any())).thenReturn(List.of(cust));

        UtilizationForecastDto result = forecastService.getForecast(3);

        assertNotNull(result);
        // 当月(m0) + 未来3ヶ月(m1, m2, m3) の計 4 点
        assertEquals(4, result.getMonthlyForecasts().size());

        // 当月 (m0): E1, E2 稼働 (2名 / 3名 = 66.7%)
        UtilizationForecastDto.MonthlyForecastDto m0 = result.getMonthlyForecasts().get(0);
        assertEquals(2, m0.getWorkingCount());
        assertEquals(1, m0.getBenchCount());
        assertEquals(3, m0.getTotalCount());
        assertEquals(66.7, m0.getUtilizationRate());

        // 翌月 (m1): E2終了 -> E1のみ稼働 (1名 / 3名 = 33.3%)
        UtilizationForecastDto.MonthlyForecastDto m1 = result.getMonthlyForecasts().get(1);
        assertEquals(1, m1.getWorkingCount());
        assertEquals(2, m1.getBenchCount());
        assertEquals(33.3, m1.getUtilizationRate());

        // ロールオフ要員一覧: E2が該当
        assertEquals(1, result.getRolloffEngineers().size());
        UtilizationForecastDto.RolloffEngineerDto rolloff = result.getRolloffEngineers().get(0);
        assertEquals(2L, rolloff.getEngineerId());
        assertEquals("Eng Two", rolloff.getEngineerName());
        assertEquals("Project Alpha", rolloff.getProjectName());
        assertEquals("Customer Inc", rolloff.getCustomerName());
        assertEquals("Sales Rep 1", rolloff.getSalesUserName());
    }

    @Test
    void testGetForecast_PreparingStatus_PreventsFalseRolloff() {
        YearMonth currentYm = YearMonth.from(LocalDate.now());
        YearMonth nextYm = currentYm.plusMonths(1);

        Engineer e1 = createEngineer(1L, "Eng One");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1));

        // c1: 今月末で終了 (autoRenew=0)
        Contract c1 = createContract(1L, 1L, currentYm.atDay(1), currentYm.atEndOfMonth(), 0);

        // c2: 翌月から開始する「準備中」(DRAFT) 契約 (FR-06 の更新ドラフト等)
        Contract c2 = createContract(2L, 1L, nextYm.atDay(1), nextYm.atEndOfMonth(), 1);
        c2.setStatus(StatusConstants.CONTRACT_PREPARING);

        when(contractMapper.selectList(any())).thenReturn(List.of(c1, c2));

        UtilizationForecastDto result = forecastService.getForecast(3);

        assertNotNull(result);
        // 翌月(m1) も c2(準備中) により稼働継続扱いとなりロールオフ一覧には入らない
        assertTrue(result.getRolloffEngineers().isEmpty(), "準備中契約がある要員はロールオフ一覧に誤報されないこと");
    }

    @Test
    void testGetForecast_AutoRenewAssumed_NotRolloff() {
        YearMonth currentYm = YearMonth.from(LocalDate.now());

        Engineer e1 = createEngineer(1L, "Eng One");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1));

        // c1: 今月末で終了だが autoRenew=1 かつ assumeRenew=true
        Contract c1 = createContract(1L, 1L, currentYm.atDay(1), currentYm.atEndOfMonth(), 1);
        when(contractMapper.selectList(any())).thenReturn(List.of(c1));

        UtilizationForecastDto result = forecastService.getForecast(3);

        assertNotNull(result);
        // 全月で稼働扱い (100.0%)
        assertEquals(100.0, result.getMonthlyForecasts().get(0).getUtilizationRate());
        assertEquals(100.0, result.getMonthlyForecasts().get(1).getUtilizationRate());
        // ロールオフ一覧は空
        assertTrue(result.getRolloffEngineers().isEmpty());
    }

    @Test
    void testGetForecast_AssumeRenewFalse_RolloffOccurs() {
        when(systemConfigService.getString(eq("forecast.assume-renew"), eq("true"))).thenReturn("false");
        YearMonth currentYm = YearMonth.from(LocalDate.now());

        Engineer e1 = createEngineer(1L, "Eng One");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1));

        // c1: autoRenew=1 だが assumeRenew=false 設定のため更新なしとみなす
        Contract c1 = createContract(1L, 1L, currentYm.atDay(1), currentYm.atEndOfMonth(), 1);
        when(contractMapper.selectList(any())).thenReturn(List.of(c1));

        when(engineerSalesService.mapPrimaryByEngineerIds(any())).thenReturn(Collections.emptyMap());

        UtilizationForecastDto result = forecastService.getForecast(3);

        assertNotNull(result);
        assertEquals(100.0, result.getMonthlyForecasts().get(0).getUtilizationRate());
        assertEquals(0.0, result.getMonthlyForecasts().get(1).getUtilizationRate());
        assertEquals(1, result.getRolloffEngineers().size());
    }

    @Test
    void testGetForecast_RenewalDecisionEnd_BecomesRolloff() {
        YearMonth currentYm = YearMonth.from(LocalDate.now());

        Engineer e1 = createEngineer(1L, "Eng One");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1));

        // c1: autoRenew=1 だが renewalDecision='END' (更新不可確定)
        Contract c1 = createContract(1L, 1L, currentYm.atDay(1), currentYm.atEndOfMonth(), 1);
        c1.setRenewalDecision("END");
        when(contractMapper.selectList(any())).thenReturn(List.of(c1));

        when(engineerSalesService.mapPrimaryByEngineerIds(any())).thenReturn(Collections.emptyMap());

        UtilizationForecastDto result = forecastService.getForecast(3);

        assertNotNull(result);
        // 翌月は非稼働 (0.0%)
        assertEquals(100.0, result.getMonthlyForecasts().get(0).getUtilizationRate());
        assertEquals(0.0, result.getMonthlyForecasts().get(1).getUtilizationRate());
        // ロールオフ一覧に1件追加
        assertEquals(1, result.getRolloffEngineers().size());
        assertEquals("END", result.getRolloffEngineers().get(0).getRenewalDecision());
    }

    @Test
    void testGetForecast_DataScopeService_Restricted() {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of(1L));

        Engineer e1 = createEngineer(1L, "Eng One");
        Engineer e2 = createEngineer(2L, "Eng Two");
        when(engineerMapper.selectList(any())).thenReturn(List.of(e1, e2));

        Contract c1 = createContract(1L, 1L, LocalDate.now().minusMonths(1), null, 1);
        when(contractMapper.selectList(any())).thenReturn(List.of(c1));

        UtilizationForecastDto result = forecastService.getForecast(3);

        assertNotNull(result);
        // allowedEngineerIds は 1L のみなので totalCount = 1
        assertEquals(1, result.getMonthlyForecasts().get(0).getTotalCount());
        assertEquals(1, result.getMonthlyForecasts().get(0).getWorkingCount());
        assertEquals(100.0, result.getMonthlyForecasts().get(0).getUtilizationRate());
    }

    @Test
    void testGetForecast_MonthsClippedToTwelve() {
        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());

        // 100 ヶ月の要求は最大 12 ヶ月 (+当月0 = 13点) にクリッピングされること
        UtilizationForecastDto result = forecastService.getForecast(100);

        assertNotNull(result);
        assertEquals(13, result.getMonthlyForecasts().size());
    }
}
