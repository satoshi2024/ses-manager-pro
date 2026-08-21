package com.ses.leave;

import com.ses.common.exception.BusinessException;
import com.ses.dto.leave.LeaveApplyRequest;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.EngineerAccountLink;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.LeaveRequestMapper;
import com.ses.service.LeaveService;
import com.ses.service.SystemConfigService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * S11-P2-01: 同一日の重なる休暇申請を並行しても「申請中」が二重挿入されない。
 */
@SpringBootTest
@ActiveProfiles("test")
class LeaveOverlapConcurrentTest {

    private static final long USER_ID = 92042L;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveRequestMapper leaveRequestMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ApprovalEngineService approvalEngineService;

    @MockBean
    private SystemConfigService systemConfigService;

    private long engineerId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70001, ?, ?, '部門', '2026-01-01', '有効')",
                "leave-ov-" + suffix, "leave-ov-" + suffix);
        long organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_organization_unit WHERE code = ?", Long.class, "leave-ov-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status, organization_id) "
                + "VALUES (?, '正社員', 'Bench', ?)", "leave-ov-eng-" + suffix, organizationId);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "leave-ov-eng-" + suffix);
        jdbcTemplate.update("INSERT INTO m_work_calendar (legal_entity_id, organization_id, engineer_id, name, valid_from, status) "
                + "VALUES (70001, ?, ?, ?, '2026-01-01', '有効')", organizationId, engineerId, "leave-ov-cal-" + suffix);
        long calendarId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_work_calendar WHERE engineer_id = ?", Long.class, engineerId);
        jdbcTemplate.update("INSERT INTO m_work_calendar_day (calendar_id, calendar_date, day_type, scheduled_minutes) "
                + "VALUES (?, '2026-08-10', '通常', 480)", calendarId);
        jdbcTemplate.update("DELETE FROM t_engineer_account_link WHERE sys_user_id = ? OR engineer_id = ?",
                USER_ID, engineerId);
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(USER_ID);
        engineerAccountLinkMapper.insert(link);

        when(approvalEngineService.request(any(ApprovalRequestCommand.class))).thenAnswer(inv -> {
            ApprovalRequest req = new ApprovalRequest();
            req.setId(900000L + System.nanoTime() % 100000);
            return req;
        });
        // 残数チェックを外し、重複ロックだけを検証する
        when(systemConfigService.getString(eq("leave.balance.source"), any())).thenReturn("external");
        when(systemConfigService.getString(eq("leave.balance.types"), any()))
                .thenReturn("有給,半休,時間休,代休,特別休暇");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM t_engineer_account_link WHERE sys_user_id = ? OR engineer_id = ?",
                USER_ID, engineerId);
    }

    @Test
    void 同一日の並行申請は片方だけが成功する() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), "n/a",
                                    List.of(new SimpleGrantedAuthority("ROLE_要員"))));
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failure.incrementAndGet();
                        return;
                    }
                    try {
                        tx.executeWithoutResult(status -> {
                            LeaveApplyRequest request = new LeaveApplyRequest();
                            request.setLeaveType("有給");
                            request.setStartDate(LocalDate.of(2026, 8, 10));
                            request.setEndDate(LocalDate.of(2026, 8, 10));
                            request.setReason("並行申請");
                            leaveService.apply(request);
                        });
                        success.incrementAndGet();
                    } catch (RuntimeException ex) {
                        failure.incrementAndGet();
                    } finally {
                        SecurityContextHolder.clearContext();
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

        long applied = leaveRequestMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.LeaveRequest>()
                        .eq(com.ses.entity.LeaveRequest::getEngineerId, engineerId)
                        .eq(com.ses.entity.LeaveRequest::getStatus, "申請中"));
        assertEquals(1, success.get(), "成功は1件");
        assertEquals(1, failure.get(), "失敗は1件");
        assertEquals(1, applied, "申請中は1件のみ");
    }
}
