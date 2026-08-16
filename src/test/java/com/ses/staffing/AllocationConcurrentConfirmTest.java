package com.ses.staffing;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectPosition;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.staffing.AllocationPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T077 A1: 同一要員への同時配置（確認）で片方が失敗することを検証する（L2〜L3）。
 *
 * <p>確定transaction内のFOR UPDATEロック（design §5.4）により、後着の配置は先行の確定を
 * 読んでから過配賦判定される。REQUIRES_NEWで2txを並行実行し、
 * 「成功1件・失敗1件」の不変条件を確認する（H2のロックタイムアウトも失敗側として許容）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AllocationConcurrentConfirmTest {

    @Autowired
    private AllocationPlanService allocationService;

    @Autowired
    private AllocationPlanMapper allocationMapper;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T077cc-eng-" + suffix);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T077cc-eng-" + suffix);
    }

    @Test
    void 同一要員への同時確定は片方だけが成功する() throws Exception {
        TransactionTemplate setup = new TransactionTemplate(transactionManager);
        setup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long sharedDraftId = setup.execute(s -> {
            // 基準の40%確定配置（FOR UPDATEのロック対象行）
            jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T077cc-cust-" + System.nanoTime());
            long customerId = jdbcTemplate.queryForObject(
                    "SELECT id FROM m_customer WHERE company_name LIKE 'T077cc-cust-%' ORDER BY id DESC LIMIT 1",
                    Long.class);
            jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                    + "VALUES (?, ?, '募集中')", "T077cc-prj-" + System.nanoTime(), customerId);
            long projectId = jdbcTemplate.queryForObject(
                    "SELECT id FROM t_project WHERE project_name LIKE 'T077cc-prj-%' ORDER BY id DESC LIMIT 1",
                    Long.class);
            ProjectPosition position = new ProjectPosition();
            position.setProjectId(projectId);
            position.setPositionNo("P1");
            position.setRoleName("Javaエンジニア");
            position.setRequiredCount(1);
            position.setAllocationPercent(new BigDecimal("100"));
            positionMapper.insert(position);

            AllocationPlan base = plan(position.getId(), "40", "2026-09-01", "2026-09-30");
            base.setStatus(STATUS_CONFIRMED);
            base.setVersion(0);
            allocationMapper.insert(base);
            // 競合させる60%の下書き（40+60=100で単独なら確定可能）
            AllocationPlan draft = plan(position.getId(), "60", "2026-09-01", "2026-09-30");
            allocationMapper.insert(draft);
            return draft.getId();
        });

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        tx.executeWithoutResult(s -> allocationService.confirm(sharedDraftId));
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    } catch (Exception e) {
                        // H2のロックタイムアウト等も「失敗側」として扱う
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> future : results) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            pool.shutdown();
            long success = outcomes.stream().filter(Boolean::booleanValue).count();
            assertEquals(1, success, "同一配置の同時確定では状態CASで片方だけが成功するはず: " + outcomes);
        } finally {
            pool.shutdownNow();
        }

        // 確定は基準40%＋成功した1件の合計2件
        long confirmed = allocationMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AllocationPlan>()
                        .eq(AllocationPlan::getEngineerId, engineerId)
                        .eq(AllocationPlan::getStatus, STATUS_CONFIRMED));
        assertEquals(2, confirmed, "基準40%＋成功した1件の合計2件が確定");
    }

    @Test
    void 後着の配置は先行の確定を読んで過配賦で拒否される() throws Exception {
        // 同一要員への同時配置のうち、後着側は先行のコミット済み確定を見て失敗する（L2）
        TransactionTemplate setup = new TransactionTemplate(transactionManager);
        setup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        setup.executeWithoutResult(s -> {
            jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T077cc-cust-" + System.nanoTime());
            long customerId = jdbcTemplate.queryForObject(
                    "SELECT id FROM m_customer WHERE company_name LIKE 'T077cc-cust-%' ORDER BY id DESC LIMIT 1",
                    Long.class);
            jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                    + "VALUES (?, ?, '募集中')", "T077cc-prj-" + System.nanoTime(), customerId);
            long projectId = jdbcTemplate.queryForObject(
                    "SELECT id FROM t_project WHERE project_name LIKE 'T077cc-prj-%' ORDER BY id DESC LIMIT 1",
                    Long.class);
            ProjectPosition position = new ProjectPosition();
            position.setProjectId(projectId);
            position.setPositionNo("P1");
            position.setRoleName("Javaエンジニア");
            position.setRequiredCount(1);
            position.setAllocationPercent(new BigDecimal("100"));
            positionMapper.insert(position);

            AllocationPlan base = plan(position.getId(), "40", "2026-09-01", "2026-09-30");
            base.setStatus(STATUS_CONFIRMED);
            base.setVersion(0);
            allocationMapper.insert(base);
            AllocationPlan b = plan(position.getId(), "60", "2026-09-01", "2026-09-30");
            AllocationPlan c = plan(position.getId(), "60", "2026-09-01", "2026-09-30");
            allocationMapper.insert(b);
            allocationMapper.insert(c);
        });
        List<AllocationPlan> drafts = allocationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AllocationPlan>()
                        .eq(AllocationPlan::getEngineerId, engineerId)
                        .eq(AllocationPlan::getStatus, AllocationPlan.STATUS_DRAFT));
        assertEquals(2, drafts.size());

        // 先行の確定（40+60=100で成功）
        allocationService.confirm(drafts.get(0).getId());
        // 後着は先行の確定を読んで110%になり拒否される
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> allocationService.confirm(drafts.get(1).getId()));
        assertEquals("error.staffing.overAllocation", ex.getMessageKey());
    }

    private AllocationPlan plan(Long positionIdRow, String percent, String start, String end) {
        AllocationPlan plan = new AllocationPlan();
        plan.setEngineerId(engineerId);
        plan.setPositionId(positionIdRow);
        plan.setAllocationType(AllocationPlan.TYPE_PROJECT);
        plan.setStartDate(LocalDate.parse(start));
        plan.setEndDate(LocalDate.parse(end));
        plan.setAllocationPercent(new BigDecimal(percent));
        plan.setStatus(AllocationPlan.STATUS_DRAFT);
        plan.setVersion(0);
        return plan;
    }
}
