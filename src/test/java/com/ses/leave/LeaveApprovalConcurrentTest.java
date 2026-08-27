package com.ses.leave;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.LeaveLedger;
import com.ses.entity.LeaveRequest;
import com.ses.mapper.LeaveLedgerMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import com.ses.service.leave.LeaveApprovalAdapter;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * S11-P1-01: 残数480分に対する並行承認はちょうど1件成功し、残高は負にならない。
 * 実DB(MySQL Testcontainers)でロックの実効性を検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class LeaveApprovalConcurrentTest {

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private LeaveApprovalAdapter adapter;

    @Autowired
    private LeaveRequestMapper leaveRequestMapper;

    @Autowired
    private LeaveLedgerMapper leaveLedgerMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EngineerSalesService engineerSalesService;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private SystemConfigService systemConfigService;

    private long engineerId;
    private long organizationId;

    @BeforeEach
    void setUp() {
        when(systemConfigService.getString(eq("leave.balance.source"), any())).thenReturn("internal");
        when(systemConfigService.getString(eq("leave.balance.types"), any()))
                .thenReturn("有給,半休,時間休,代休,特別休暇");
        when(systemConfigService.getString(eq("leave.sales-notification.types"), any()))
                .thenReturn("有給,特別休暇");
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')",
                "leave-cc-" + suffix, "leave-cc-" + suffix);
        organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_organization_unit WHERE code = ?", Long.class, "leave-cc-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) "
                + "VALUES (?, '正社員', 'Bench', ?)", "leave-cc-eng-" + suffix, organizationId);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "leave-cc-eng-" + suffix);
        jdbcTemplate.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, status) "
                + "VALUES (70001, ?, ?, ?, '2026-01-01', '有効')", organizationId, engineerId, "leave-cc-cal-" + suffix);
        long calendarId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_work_calendar WHERE engineer_id = ?", Long.class, engineerId);
        jdbcTemplate.update("INSERT INTO m_work_calendar_day (calendar_id, calendar_date, day_type, scheduled_minutes) "
                + "VALUES (?, '2026-08-03', '通常', 480)", calendarId);
        jdbcTemplate.update("INSERT INTO m_work_calendar_day (calendar_id, calendar_date, day_type, scheduled_minutes) "
                + "VALUES (?, '2026-08-04', '通常', 480)", calendarId);
        jdbcTemplate.update("INSERT INTO t_leave_ledger "
                + "(engineer_id, legal_entity_id, leave_type, ledger_type, amount_minutes, entry_date, source, version) "
                + "VALUES (?, 70001, '有給', 'GRANT', 480, '2026-04-01', 'manual', 0)", engineerId);
    }

    @Test
    void 残数480に対する2件の並行承認は1件だけ成功し残高が負にならない() throws Exception {
        TransactionTemplate setup = new TransactionTemplate(transactionManager);
        setup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<Long> leaveIds = setup.execute(status -> {
            LeaveRequest a = insertLeave(LocalDate.of(2026, 8, 3), 480);
            LeaveRequest b = insertLeave(LocalDate.of(2026, 8, 4), 480);
            return List.of(a.getId(), b.getId());
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Long leaveId : leaveIds) {
                futures.add(pool.submit(() -> {
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        unexpected.compareAndSet(null, e);
                        return;
                    }
                    try {
                        tx.executeWithoutResult(status -> {
                            LeaveRequest leave = leaveRequestMapper.selectById(leaveId);
                            adapter.applyApproved(approvalRequest(leave));
                        });
                        success.incrementAndGet();
                    } catch (BusinessException ex) {
                        // REV-RP-P2-003: 409 concurrent または残数不足 messageKey
                        boolean expected = ex.getCode() == 409
                                || "error.attendance.concurrent".equals(ex.getMessageKey())
                                || "error.leave.balanceInsufficient".equals(ex.getMessageKey());
                        if (expected) {
                            failure.incrementAndGet();
                        } else {
                            unexpected.compareAndSet(null, ex);
                        }
                    } catch (RuntimeException ex) {
                        unexpected.compareAndSet(null, ex);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertNull(unexpected.get(), () -> "想定外の例外: " + unexpected.get());
        assertEquals(1, success.get(), "成功はちょうど1件");
        assertEquals(1, failure.get(), "失敗はちょうど1件");

        int balance = leaveLedgerMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LeaveLedger>()
                                .eq(LeaveLedger::getEngineerId, engineerId)
                                .eq(LeaveLedger::getLeaveType, "有給"))
                .stream()
                .mapToInt(row -> "GRANT".equals(row.getLedgerType())
                        ? row.getAmountMinutes() : -row.getAmountMinutes())
                .sum();
        assertTrue(balance >= 0, "残高が負になってはいけない: " + balance);
        assertEquals(0, balance, "480消化後の残高は0");
    }

    private LeaveRequest insertLeave(LocalDate day, int minutes) {
        LeaveRequest leave = LeaveRequest.builder()
                .engineerId(engineerId)
                .legalEntityId(70001L)
                .organizationId(organizationId)
                .leaveType("有給")
                .startDate(day)
                .endDate(day)
                .requestedMinutes(minutes)
                .status("申請中")
                .version(0)
                .createdBy(1L)
                .build();
        leaveRequestMapper.insert(leave);
        return leave;
    }

    private ApprovalRequest approvalRequest(LeaveRequest leave) {
        ApprovalRequest request = new ApprovalRequest();
        request.setRequestType(LeaveApprovalAdapter.REQUEST_TYPE);
        request.setTargetId(leave.getId());
        request.setTargetVersion(leave.getVersion() == null ? 0L : leave.getVersion().longValue());
        return request;
    }
}
