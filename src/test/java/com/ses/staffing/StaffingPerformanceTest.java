package com.ses.staffing;

import com.ses.dto.staffing.HeatmapDto;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectPosition;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.staffing.StaffingHeatmapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T080（M）: 代表データ量（要員200・position50・配置300）での需給集計の
 * p95レイテンシとheap増加の実測。
 *
 * <p>境界はCIで安定するよう余裕を持たせる（p95 < 10s・集計セル数はグループ×月に比例）。
 * 実測値はreview-ledgerに記録する。
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:staffing-performance;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL")
@ActiveProfiles("test")
@Transactional
@Tag("performance")
class StaffingPerformanceTest {

    @Autowired
    private StaffingHeatmapService heatmapService;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T080perf-" + suffix);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T080perf-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T080perf-prj-" + suffix, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T080perf-prj-" + suffix);
    }

    @Test
    void 代表データ量で需給集計のp95とheap増加を実測する() {
        // ---- 代表データ: 要員200・position50・配置300 ----
        List<Long> engineerIds = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, expected_unit_price) "
                    + "VALUES (?, '正社員', 'Bench', 600000)", "T080perf-eng-" + i);
            engineerIds.add(jdbcTemplate.queryForObject(
                    "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T080perf-eng-" + i));
        }
        List<Long> positionIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ProjectPosition position = new ProjectPosition();
            position.setProjectId(projectId);
            position.setPositionNo("P" + i);
            position.setRoleName("ロール" + (i % 10));
            position.setLocation("都市" + (i % 5));
            position.setRequiredCount(2);
            position.setAllocationPercent(new BigDecimal("100"));
            position.setSkillsJson("[\"スキル" + (i % 8) + "\"]");
            position.setStartDate(LocalDate.of(2026, 9, 1));
            position.setEndDate(LocalDate.of(2026, 12, 31));
            positionMapper.insert(position);
            positionIds.add(position.getId());
        }
        for (int i = 0; i < 300; i++) {
            jdbcTemplate.update("INSERT INTO t_allocation_plan "
                    + "(engineer_id, position_id, allocation_type, start_date, end_date, allocation_percent, status, version) "
                    + "VALUES (?, ?, '案件', '2026-09-01', '2026-12-31', 100, '確定', 0)",
                    engineerIds.get(i % 200), positionIds.get(i % 50));
        }

        // ---- warmup ----
        heatmapService.heatmap(LocalDate.of(2026, 8, 1));

        // ---- 実測（5回） ----
        List<Long> latencies = new ArrayList<>();
        Runtime runtime = Runtime.getRuntime();
        long heapBefore = usedHeap(runtime);
        HeatmapDto last = null;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            last = heatmapService.heatmap(LocalDate.of(2026, 8, 1));
            latencies.add((System.nanoTime() - start) / 1_000_000);
        }
        long heapAfter = usedHeap(runtime);
        latencies.sort(Comparator.naturalOrder());
        long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        long heapDelta = Math.max(0, heapAfter - heapBefore);

        System.out.println("T080-M perf: p95=" + p95 + "ms latencies=" + latencies
                + " heapDelta=" + heapDelta + "KB");

        // ---- 境界 ----
        assertTrue(p95 < 10_000, "p95が10秒未満であること（実測: " + p95 + "ms）");
        assertTrue(last.getTotals().size() == 24, "24か月の集計");
        int cells = last.getSkill().size() * 24 + last.getRole().size() * 24 + last.getLocation().size() * 24;
        // グループ数（3次元×各グループ＋未割当）×月。200要員×730日=146,000の直積には比例しない
        assertTrue(cells <= 800, "セル数はグループ×月に比例（" + cells + "）");
        // heap増加は定数バウンド（200要員×730日=146,000要素の直積を作っていないことの間接証拠）
        assertTrue(heapDelta < 256 * 1024, "heap増加が256MB未満であること（実測: " + heapDelta + "KB）");
    }

    private long usedHeap(Runtime runtime) {
        runtime.gc();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024;
    }
}
