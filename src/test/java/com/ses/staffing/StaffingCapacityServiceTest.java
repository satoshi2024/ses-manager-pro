package com.ses.staffing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.LeaveRequest;
import com.ses.entity.ProjectPosition;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.ContractService;
import com.ses.service.UtilizationCalcService;
import com.ses.service.staffing.StaffingCapacityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T076 F2: 需給集計（capacity aggregation）の定向test（L2〜L3）。
 * plan/actual二重計上0・更新済契約の反映・退職の反映・休暇が稼働可能日数を減らすが契約FTEを変えない・
 * 稼働率がdashboard KPI（UtilizationCalcService）と一致することを検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StaffingCapacityServiceTest {

    private static final YearMonth SEPT = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    @Autowired
    private StaffingCapacityService capacityService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private AllocationPlanMapper allocationMapper;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private UtilizationCalcService utilizationCalcService;

    @Autowired
    private org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;
    private long projectId;
    private long positionId;
    private Engineer engineer;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T076cap-" + suffix);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T076cap-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T076cap-prj-" + suffix, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T076cap-prj-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T076cap-eng-" + suffix);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T076cap-eng-" + suffix);
        engineer = new Engineer();
        engineer.setId(engineerId);
        engineer.setStatus("稼動中");
        positionId = insertPosition(projectId, "P1");
    }

    @Test
    void actualとplanが二重計上されない() {
        Contract contract = createContract(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 1, null);

        // 同一engineerのplan（source_contract_id IS NULL）を別ポジションで追加
        long position2 = insertPosition(projectId, "P2");
        AllocationPlan plan = new AllocationPlan();
        plan.setEngineerId(engineerId);
        plan.setPositionId(position2);
        plan.setAllocationType(AllocationPlan.TYPE_PROJECT);
        plan.setStartDate(LocalDate.of(2026, 9, 1));
        plan.setEndDate(LocalDate.of(2026, 9, 30));
        plan.setAllocationPercent(new BigDecimal("50"));
        plan.setStatus(STATUS_CONFIRMED);
        plan.setVersion(0);
        allocationMapper.insert(plan);

        StaffingCapacityService.EngineerMonthSupply supply =
                capacityService.supply(engineer, SEPT, LocalDate.of(2026, 8, 1));

        // actualは契約から1回だけ、planはsource_contract_id IS NULLの行だけ（WHERE句で排他）
        assertEquals(0, new BigDecimal("100").compareTo(supply.actualFte()));
        assertEquals(0, new BigDecimal("50").compareTo(supply.planFte()));
        assertEquals(0, new BigDecimal("150").compareTo(supply.totalFte()));
        assertTrue(supply.workingDays() >= 20);
    }

    @Test
    void 更新済契約は終了日以降もactualとして供給される() {
        // renewalDecision は DTO に無い ALWAYS 列のため、無引数 updateWithBusinessRules では
        // old から回填され上書きできない。単列更新 API を使う。
        Contract contract = createContract(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 1, null);
        contractService.updateRenewalDecision(contract.getId(), "CONTINUE");

        StaffingCapacityService.EngineerMonthSupply oct =
                capacityService.supply(engineer, OCT, LocalDate.of(2026, 8, 1));
        assertEquals(0, new BigDecimal("100").compareTo(oct.actualFte()),
                "更新継続（CONTINUE）は終了日以降もactualとして計上される");

        contractService.updateRenewalDecision(contract.getId(), "END");
        StaffingCapacityService.EngineerMonthSupply octEnd =
                capacityService.supply(engineer, OCT, LocalDate.of(2026, 8, 1));
        assertEquals(0, new BigDecimal("0").compareTo(octEnd.actualFte()),
                "更新不要（END）は終了日で供給が止まる");
    }

    @Test
    void 退場予定は最終契約終了日以降の供給が0になる() {
        createContract(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31), 1, null);
        engineer.setStatus("退場予定");

        StaffingCapacityService.EngineerMonthSupply oct =
                capacityService.supply(engineer, OCT, LocalDate.of(2026, 8, 1));
        assertEquals(0, new BigDecimal("100").compareTo(oct.actualFte()));

        StaffingCapacityService.EngineerMonthSupply nov =
                capacityService.supply(engineer, NOV, LocalDate.of(2026, 8, 1));
        assertEquals(0, nov.availableDays(), "退場予定は契約終了後の稼働可能日数が0になる");
        assertEquals(0, new BigDecimal("0").compareTo(nov.actualFte()));
    }

    @Test
    void 休暇は稼働可能日数を減らすが契約FTEを変えない() {
        createContract(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 1, null);

        StaffingCapacityService.EngineerMonthSupply before =
                capacityService.supply(engineer, SEPT, LocalDate.of(2026, 8, 1));
        assertEquals(0, new BigDecimal("100").compareTo(before.actualFte()));
        assertEquals(0, before.leaveDays());

        // 承認済み休暇を2日入れる
        LeaveRequest leave = new LeaveRequest();
        leave.setEngineerId(engineerId);
        leave.setLeaveType("有給");
        leave.setStartDate(LocalDate.of(2026, 9, 14));
        leave.setEndDate(LocalDate.of(2026, 9, 15));
        leave.setStatus("承認済");
        leave.setRequestedMinutes(960);
        jdbcTemplate.update("INSERT INTO t_leave_request "
                + "(engineer_id, leave_type, start_date, end_date, status, requested_minutes, version, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, 1)", engineerId, "有給",
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15), "承認済", 960);
        // 同一transaction内の同一パラメータ再実行はMyBatisの1次キャッシュ（SqlSession cache）に
        // 当たるため、挿入後にキャッシュをクリアしてから再集計する
        sqlSessionTemplate.clearCache();

        StaffingCapacityService.EngineerMonthSupply after =
                capacityService.supply(engineer, SEPT, LocalDate.of(2026, 8, 1));
        assertEquals(before.workingDays() - 2, after.availableDays(), "休暇は稼働可能日数を減らす");
        assertEquals(2, after.leaveDays());
        assertEquals(0, new BigDecimal("100").compareTo(after.actualFte()), "契約FTE自体は変わらない");
    }

    @Test
    void 稼働率はdashboardKPIと同一口径になる() {
        createContract(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 1, null);
        Engineer bench = new Engineer();
        bench.setId(engineerId + 1000000L);
        bench.setStatus("Bench");

        List<Contract> contracts = contractService.list(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId));
        Map<Long, List<Contract>> byEngineer = Map.of(engineerId, contracts);
        boolean assume = true;

        UtilizationCalcService.UtilizationSnapshot dashboard =
                utilizationCalcService.calc(SEPT, List.of(engineer, bench), byEngineer, assume);
        UtilizationCalcService.UtilizationSnapshot staffing =
                capacityService.utilization(SEPT, List.of(engineer, bench), byEngineer, assume);

        assertEquals(dashboard.getWorkingCount(), staffing.getWorkingCount());
        assertEquals(dashboard.getBenchCount(), staffing.getBenchCount());
        assertEquals(dashboard.getUtilizationRate(), staffing.getUtilizationRate(), 0.001);
    }

    @Test
    void supplyBatchは指定した要員の範囲だけを返す() {
        createContract(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 1, null);
        Engineer other = new Engineer();
        other.setId(engineerId + 2000000L);
        other.setStatus("Bench");

        List<StaffingCapacityService.EngineerMonthSupply> rows =
                capacityService.supplyBatch(List.of(engineer, other), SEPT, OCT, LocalDate.of(2026, 8, 1));
        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.engineerId().equals(engineerId)
                || r.engineerId().equals(other.getId())));
    }

    // ---------------------------------------------------------------

    private Contract createContract(LocalDate start, LocalDate end, int autoRenew, String renewalDecision) {
        Contract contract = new Contract();
        contract.setEngineerId(engineerId);
        contract.setProjectId(projectId);
        contract.setCustomerId(jdbcTemplate.queryForObject(
                "SELECT customer_id FROM t_project WHERE id = ?", Long.class, projectId));
        contract.setContractType("準委任");
        contract.setStartDate(start);
        contract.setEndDate(end);
        contract.setSellingPrice(new BigDecimal("800000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setPositionId(positionId);
        contract.setAutoRenew(autoRenew);
        contract.setRenewalDecision(renewalDecision);
        contractService.saveWithBusinessRules(contract);
        return contract;
    }

    private long insertPosition(long projectIdRow, String no) {
        ProjectPosition position = new ProjectPosition();
        position.setProjectId(projectIdRow);
        position.setPositionNo(no);
        position.setRoleName("Javaエンジニア");
        position.setRequiredCount(1);
        position.setAllocationPercent(new BigDecimal("100"));
        positionMapper.insert(position);
        return position.getId();
    }
}
