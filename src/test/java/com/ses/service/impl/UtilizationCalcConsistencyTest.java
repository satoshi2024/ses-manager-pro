package com.ses.service.impl;

import com.ses.common.constant.StatusConstants;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ダッシュボードKPIの当月稼働率と、将来稼働率予測(FR-07)の当月値が一致することを検証する。
 *
 * <p>FR-07 Requirement 1.3 /「完了条件」: 予測の口径は既存ダッシュボードの当月稼働率と一致すること。
 * 両者は {@link UtilizationCalcServiceImpl} を共用するため構造的に一致するが、
 * 片方だけ独自集計に戻した場合(A7-09 の口径分裂の再発)を検知するための回帰テスト。
 */
@ExtendWith(MockitoExtension.class)
class UtilizationCalcConsistencyTest {

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
    private WorkRecordMapper workRecordMapper;
    @Mock
    private EngineerSkillMapper engineerSkillMapper;
    @Mock
    private ProposalMapper proposalMapper;
    @Mock
    private EngineerSalesService engineerSalesService;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private DataScopeService dataScopeService;

    // 共通口径サービスは純粋ロジックのため実体を注入する
    @Spy
    private MonthlyRevenueCalcServiceImpl monthlyRevenueCalcService = new MonthlyRevenueCalcServiceImpl();
    @Spy
    private UtilizationCalcServiceImpl utilizationCalcService = new UtilizationCalcServiceImpl();

    @InjectMocks
    private DashboardServiceImpl dashboardService;
    @InjectMocks
    private UtilizationForecastServiceImpl forecastService;

    private Engineer engineer(Long id, String status) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setFullName("Engineer " + id);
        e.setInitialName("E");
        e.setStatus(status);
        return e;
    }

    private Contract contract(Long id, Long engineerId, LocalDate start, LocalDate end, String status) {
        Contract c = new Contract();
        c.setId(id);
        c.setContractNo("C" + id);
        c.setEngineerId(engineerId);
        c.setStartDate(start);
        c.setEndDate(end);
        c.setStatus(status);
        c.setAutoRenew(0);
        return c;
    }

    @Test
    void currentMonthUtilization_matchesBetweenDashboardKpiAndForecast() {
        YearMonth currentYm = YearMonth.from(LocalDate.now());
        LocalDate farFuture = currentYm.plusMonths(6).atEndOfMonth();

        // Engineer.status と契約実態が意図的にズレたデータ。
        // 契約ベース(共通口径)では E1/E2/E5 の3名が稼働 = 60.0%、
        // 旧 Engineer.status ベースでは E1/E4 の2名 = 40.0% となり、値が異なる。
        Engineer e1 = engineer(1L, StatusConstants.ENGINEER_ACTIVE);   // 稼動中 + 有効契約あり
        Engineer e2 = engineer(2L, "提案中");                           // 提案中だが有効契約あり
        Engineer e3 = engineer(3L, "Bench");                           // 契約なし
        Engineer e4 = engineer(4L, StatusConstants.ENGINEER_ACTIVE);   // 稼動中だが契約なし
        Engineer e5 = engineer(5L, "提案中");                           // 提案中だが有効契約あり
        List<Engineer> engineers = List.of(e1, e2, e3, e4, e5);

        List<Contract> contracts = List.of(
                contract(1L, 1L, currentYm.minusMonths(3).atDay(1), farFuture, StatusConstants.CONTRACT_ACTIVE),
                contract(2L, 2L, currentYm.minusMonths(1).atDay(1), farFuture, StatusConstants.CONTRACT_ACTIVE),
                contract(5L, 5L, currentYm.atDay(1), farFuture, StatusConstants.CONTRACT_ACTIVE)
        );

        when(engineerMapper.selectList(any())).thenReturn(engineers);
        when(contractMapper.selectList(any())).thenReturn(contracts);
        when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(systemConfigService.getString(eq("forecast.assume-renew"), any())).thenReturn("true");
        lenient().when(engineerSalesService.mapPrimaryByEngineerIds(any())).thenReturn(Collections.emptyMap());

        DashboardSummaryDto summary = dashboardService.getSummary(null);
        UtilizationForecastDto forecast = forecastService.getForecast(3);

        double kpiUtilization = summary.getKpi().getUtilization();
        UtilizationForecastDto.MonthlyForecastDto currentMonth = forecast.getMonthlyForecasts().get(0);

        // 予測の先頭要素は当月であること
        assertEquals(currentYm.toString(), currentMonth.getYearMonth());
        // 口径一致 (FR-07 Requirement 1.3)
        assertEquals(kpiUtilization, currentMonth.getUtilizationRate(),
                "ダッシュボードKPIの当月稼働率と予測の当月値は同一口径でなければならない");
        // 契約ベース口径の期待値(旧 Engineer.status ベースなら 40.0 となり検知できる)
        assertEquals(60.0, kpiUtilization);
        assertEquals(3, currentMonth.getWorkingCount());
        assertEquals(5, currentMonth.getTotalCount());
    }
}
