package com.ses.staffing;

import com.ses.common.exception.BusinessException;
import com.ses.dto.staffing.HeatmapDto;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectPosition;
import com.ses.service.staffing.StaffingHeatmapService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T078 B1: 需給heatmapの定向test（L2〜L3）。
 * 全社合計=内訳合計・FTE口径・24か月上限・HRのbench cost mask・応答サイズの上限を検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StaffingHeatmapServiceTest {

    @Autowired
    private StaffingHeatmapService heatmapService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate;

    private long engineerId1;
    private long engineerId2;
    private long projectId;
    private long positionId;
    private long benchEngineerId;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T078hm-" + suffix);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T078hm-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T078hm-prj-" + suffix, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T078hm-prj-" + suffix);

        // 要員2名（Javaスキル）＋bench要員1名（Pythonスキル）
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, expected_unit_price) "
                + "VALUES (?, '正社員', '稼動中', 800000)", "T078hm-eng1-" + suffix);
        engineerId1 = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T078hm-eng1-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, expected_unit_price) "
                + "VALUES (?, '正社員', '稼動中', 900000)", "T078hm-eng2-" + suffix);
        engineerId2 = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T078hm-eng2-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, expected_unit_price) "
                + "VALUES (?, '正社員', 'Bench', 600000)", "T078hm-bench-" + suffix);
        benchEngineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T078hm-bench-" + suffix);

        // スキルタグ: Java / Python
        jdbcTemplate.update("INSERT INTO m_skill_tag (skill_name, category) VALUES (?, '言語')",
                "T078hm-java-" + suffix);
        long javaSkillId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_skill_tag WHERE skill_name = ?", Long.class, "T078hm-java-" + suffix);
        jdbcTemplate.update("INSERT INTO m_skill_tag (skill_name, category) VALUES (?, '言語')",
                "T078hm-python-" + suffix);
        long pythonSkillId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_skill_tag WHERE skill_name = ?", Long.class, "T078hm-python-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer_skill (engineer_id, skill_id, proficiency) "
                + "VALUES (?, ?, '上級')", engineerId1, javaSkillId);
        jdbcTemplate.update("INSERT INTO t_engineer_skill (engineer_id, skill_id, proficiency) "
                + "VALUES (?, ?, '上級')", engineerId2, javaSkillId);
        jdbcTemplate.update("INSERT INTO t_engineer_skill (engineer_id, skill_id, proficiency) "
                + "VALUES (?, ?, '中級')", benchEngineerId, pythonSkillId);

        // position: Javaエンジニア2名・東京・100%
        positionId = insertPosition("P1", "T078hm-role-" + suffix, "東京",
                "[\"T078hm-java-" + suffix + "\"]", 2, "100");
    }

    @Test
    void 全社合計と内訳合計が次元ごとに一致する() {
        // 供給側: 要員2名をpositionへ確定配置（100%）
        insertConfirmedAllocation(engineerId1, positionId, "2026-09-01", "2026-09-30", "100");
        insertConfirmedAllocation(engineerId2, positionId, "2026-09-01", "2026-09-30", "100");

        HeatmapDto dto = heatmapService.heatmap(LocalDate.of(2026, 8, 1),
                YearMonth.of(2026, 9), YearMonth.of(2026, 9));

        for (String dimension : List.of("skill", "role", "location")) {
            List<HeatmapDto.DimensionRow> rows = rowsOf(dto, dimension);
            BigDecimal demandSum = sumCells(rows, "demand");
            BigDecimal supplySum = sumCells(rows, "supply");
            BigDecimal benchSum = sumCells(rows, "benchCost");
            HeatmapDto.MonthCell total = dto.getTotals().get(0);
            assertEquals(0, demandSum.compareTo(total.getDemandFte()),
                    dimension + " の需要合計が全社合計と一致する");
            assertEquals(0, supplySum.compareTo(total.getSupplyFte()),
                    dimension + " の供給合計が全社合計と一致する");
            assertEquals(0, benchSum.compareTo(total.getBenchCost()),
                    dimension + " のbench cost合計が全社合計と一致する");
        }
    }

    @Test
    void 需要FTEは募集人数と稼働率と期間比で計算される() {
        // positionは9/1〜9/30（22営業日）・2人・100% → 需要 = 2.00 FTE
        HeatmapDto dto = heatmapService.heatmap(LocalDate.of(2026, 8, 1),
                YearMonth.of(2026, 9), YearMonth.of(2026, 9));
        List<HeatmapDto.DimensionRow> roleRows = rowsOf(dto, "role");
        HeatmapDto.DimensionRow javaRow = roleRows.stream()
                .filter(r -> ("T078hm-role-" + suffix).equals(r.getGroup()))
                .findFirst().orElse(null);
        assertNotNull(javaRow, "role次元にJavaエンジニア行がある");
        assertEquals(0, new BigDecimal("2.00").compareTo(javaRow.getCells().get(0).getDemandFte()),
                "需要 = 2人 × 100% × 22/22日 = 2.00 FTE");

        // 期間比の按分: 9/16開始（11営業日）のpositionは需要1.00 FTE
        String suffix2 = String.valueOf(System.nanoTime());
        String role2 = "T078hm-role2-" + suffix2;
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T078hm2-" + suffix2);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T078hm2-" + suffix2);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T078hm2-prj-" + suffix2, customerId);
        long project2 = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T078hm2-prj-" + suffix2);
        jdbcTemplate.update("INSERT INTO t_project_position "
                + "(project_id, position_no, role_name, required_count, start_date, end_date, allocation_percent, status, version) "
                + "VALUES (?, 'P1', '" + role2 + "', 2, '2026-09-16', '2026-09-30', 100, '募集中', 0)",
                project2);
        // 同一transaction内の同一パラメータ再実行はMyBatisの1次キャッシュに当たるためクリアする
        sqlSessionTemplate.clearCache();
        HeatmapDto dto2 = heatmapService.heatmap(LocalDate.of(2026, 8, 1),
                YearMonth.of(2026, 9), YearMonth.of(2026, 9));
        HeatmapDto.DimensionRow feRow = rowsOf(dto2, "role").stream()
                .filter(r -> ("T078hm-role2-" + suffix2).equals(r.getGroup()))
                .findFirst().orElse(null);
        assertNotNull(feRow);
        assertEquals(0, new BigDecimal("1.00").compareTo(feRow.getCells().get(0).getDemandFte()),
                "需要 = 2人 × 100% × 11/22日 = 1.00 FTE");
    }

    @Test
    void 計画window24か月を超える要求は拒否される() {
        YearMonth beyond = YearMonth.from(LocalDate.now().plusMonths(25));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> heatmapService.heatmap(LocalDate.now(), YearMonth.from(LocalDate.now()), beyond));
        assertEquals("error.staffing.horizonExceeded", ex.getMessageKey());

        BusinessException invalid = assertThrows(BusinessException.class,
                () -> heatmapService.heatmap(LocalDate.now(),
                        YearMonth.of(2026, 10), YearMonth.of(2026, 9)));
        assertEquals("error.staffing.invalidPeriod", invalid.getMessageKey());
    }

    @Test
    void HRロールではbenchCostがmaskされ管理者では表示される() {
        insertConfirmedAllocation(engineerId1, positionId, "2026-09-01", "2026-09-30", "100");
        // bench要員（供給なし）を残す

        authenticate("hr-user", "HR");
        HeatmapDto hr = heatmapService.heatmap(LocalDate.of(2026, 8, 1),
                YearMonth.of(2026, 9), YearMonth.of(2026, 9));
        for (HeatmapDto.MonthCell cell : hr.getTotals()) {
            assertNull(cell.getBenchCost(), "HRにはbench costを表示しない");
        }

        authenticate("admin-user", "管理者");
        HeatmapDto admin = heatmapService.heatmap(LocalDate.of(2026, 8, 1),
                YearMonth.of(2026, 9), YearMonth.of(2026, 9));
        assertTrue(admin.getTotals().stream()
                        .anyMatch(cell -> cell.getBenchCost() != null && cell.getBenchCost().signum() > 0),
                "管理者にはbench cost（待機要員のコスト）が表示される");
    }

    @Test
    void 大量要員でも応答サイズが要員数掛ける日数に比例しない() {
        // 50要員を追加（配置なし＝bench）して24か月集計する
        for (int i = 0; i < 50; i++) {
            jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, expected_unit_price) "
                    + "VALUES (?, '正社員', 'Bench', 500000)", "T078hm-bulk-" + i);
        }
        HeatmapDto dto = heatmapService.heatmap(LocalDate.of(2026, 8, 1));
        int months = dto.getTotals().size();
        assertEquals(24, months, "24か月分の集計");
        // セル数 = (3次元×グループ数)×月。要員数×日数（50×730）には比例しない
        int cellCount = rowsOf(dto, "skill").stream().mapToInt(r -> r.getCells().size()).sum()
                + rowsOf(dto, "role").stream().mapToInt(r -> r.getCells().size()).sum()
                + rowsOf(dto, "location").stream().mapToInt(r -> r.getCells().size()).sum();
        assertTrue(cellCount <= 300, "セル数はグループ数×月に比例するはず: " + cellCount);
    }

    // ---------------------------------------------------------------

    private long insertPosition(String no, String role, String location, String skillsJson,
                                int requiredCount, String percent) {
        ProjectPosition position = new ProjectPosition();
        position.setProjectId(projectId);
        position.setPositionNo(no);
        position.setRoleName(role);
        position.setLocation(location);
        position.setSkillsJson(skillsJson);
        position.setRequiredCount(requiredCount);
        position.setAllocationPercent(new BigDecimal(percent));
        position.setStartDate(LocalDate.of(2026, 9, 1));
        position.setEndDate(LocalDate.of(2026, 9, 30));
        jdbcTemplate.update("INSERT INTO t_project_position "
                + "(project_id, position_no, role_name, required_count, skills_json, start_date, end_date, location, allocation_percent, status, version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '募集中', 0)",
                projectId, no, role, requiredCount, skillsJson, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), location, new BigDecimal(percent));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_project_position WHERE position_no = ? AND project_id = ?",
                Long.class, no, projectId);
    }

    private void insertConfirmedAllocation(long engineerIdRow, long positionIdRow, String start, String end, String percent) {
        AllocationPlan plan = new AllocationPlan();
        plan.setEngineerId(engineerIdRow);
        plan.setPositionId(positionIdRow);
        plan.setAllocationType(AllocationPlan.TYPE_PROJECT);
        plan.setStartDate(LocalDate.parse(start));
        plan.setEndDate(LocalDate.parse(end));
        plan.setAllocationPercent(new BigDecimal(percent));
        plan.setStatus(STATUS_CONFIRMED);
        plan.setVersion(0);
        jdbcTemplate.update("INSERT INTO t_allocation_plan "
                + "(engineer_id, position_id, allocation_type, start_date, end_date, allocation_percent, status, version) "
                + "VALUES (?, ?, '案件', ?, ?, ?, ?, 0)",
                engineerIdRow, positionIdRow, LocalDate.parse(start), LocalDate.parse(end),
                new BigDecimal(percent), STATUS_CONFIRMED);
    }

    private List<HeatmapDto.DimensionRow> rowsOf(HeatmapDto dto, String dimension) {
        return switch (dimension) {
            case "skill" -> dto.getSkill();
            case "role" -> dto.getRole();
            case "location" -> dto.getLocation();
            default -> List.of();
        };
    }

    private BigDecimal sumCells(List<HeatmapDto.DimensionRow> rows, String field) {
        BigDecimal sum = BigDecimal.ZERO;
        for (HeatmapDto.DimensionRow row : rows) {
            for (HeatmapDto.MonthCell cell : row.getCells()) {
                BigDecimal value = switch (field) {
                    case "demand" -> cell.getDemandFte();
                    case "supply" -> cell.getSupplyFte();
                    default -> cell.getBenchCost();
                };
                if (value != null) {
                    sum = sum.add(value);
                }
            }
        }
        return sum;
    }

    private void authenticate(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }
}
