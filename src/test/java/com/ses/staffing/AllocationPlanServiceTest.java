package com.ses.staffing;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AllocationPlan;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.ProjectPosition;
import com.ses.entity.SysUser;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.staffing.AllocationPlanService;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static com.ses.entity.AllocationPlan.STATUS_DISCARDED;
import static com.ses.entity.AllocationPlan.STATUS_DRAFT;
import static com.ses.entity.AllocationPlan.TYPE_BENCH;
import static com.ses.entity.AllocationPlan.TYPE_INTERNAL;
import static com.ses.entity.AllocationPlan.TYPE_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T075 F1: 過配賦判定（日単位）・例外承認・状態機械・scenario外の区間代数の定向test（L2〜L3）。
 * 受入条件R5: 50+50許可 / 60+50拒否 / 重複なし許可 / 1日重複拒否 / 隣接OK・同日NG / 例外承認で許可。
 *
 * <p>reviseのrollback（変更前の区間へ戻る）はREQUIRES_NEWなtransactionで実行し、
 * 実DB状態で検証する（外側transaction内ではrollback前の書込みが見えるため）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AllocationPlanServiceTest {

    private static final LocalDate SEPT = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP30 = LocalDate.of(2026, 9, 30);

    @Autowired
    private AllocationPlanService allocationService;

    @Autowired
    private AllocationPlanMapper allocationMapper;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private ApprovalEngineService approvalEngineService;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private ApprovalRouteMapper approvalRouteMapper;

    @Autowired
    private ApprovalRouteStepMapper approvalRouteStepMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long engineerId;
    private long positionId;
    private long applicantUserId;
    private long approverUserId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T075alloc-" + suffix);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T075alloc-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T075alloc-prj-" + suffix, customerId);
        long projectIdRow = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T075alloc-prj-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T075alloc-eng-" + suffix);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T075alloc-eng-" + suffix);

        ProjectPosition position = new ProjectPosition();
        position.setProjectId(projectIdRow);
        position.setPositionNo("P1");
        position.setRoleName("Javaエンジニア");
        position.setRequiredCount(2);
        position.setAllocationPercent(new BigDecimal("100"));
        positionMapper.insert(position);
        positionId = position.getId();

        applicantUserId = insertUser("t075-applicant");
        approverUserId = insertUser("t075-approver");
        insertRoute("staffing.overallocation", List.of(List.of(approverUserId)));
        authenticate(applicantUserId, "管理者");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------
    // 過配賦の日単位判定（R5）
    // ---------------------------------------------------------------

    @Test
    void 完全重複の50パーセントプラス50パーセントは許可される() {
        AllocationPlan a = save(percent(50), SEPT, SEP30);
        allocationService.confirm(a.getId());
        AllocationPlan b = save(percent(50), SEPT, SEP30);
        AllocationPlan confirmed = allocationService.confirm(b.getId());
        assertEquals(STATUS_CONFIRMED, confirmed.getStatus());
    }

    @Test
    void 完全重複の60パーセントプラス50パーセントは拒否される() {
        AllocationPlan a = save(percent(60), SEPT, SEP30);
        allocationService.confirm(a.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(plan(percent(50), SEPT, SEP30)));
        assertEquals("error.staffing.overAllocation", ex.getMessageKey());
    }

    @Test
    void 重複なしの60パーセントプラス50パーセントは許可される() {
        AllocationPlan a = save(percent(60), SEPT, LocalDate.of(2026, 9, 15));
        allocationService.confirm(a.getId());
        AllocationPlan b = save(percent(50), LocalDate.of(2026, 9, 16), SEP30);
        allocationService.confirm(b.getId());
        assertEquals(STATUS_CONFIRMED, allocationMapper.selectById(b.getId()).getStatus());
    }

    @Test
    void 一日だけ重複する60パーセントプラス50パーセントはその日で拒否される() {
        AllocationPlan a = save(percent(60), SEPT, LocalDate.of(2026, 9, 15));
        allocationService.confirm(a.getId());
        // 前end_date(9/15)と次start_date(9/15)は同日＝重複あり（両端inclusive）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(plan(percent(50), LocalDate.of(2026, 9, 15), SEP30)));
        assertEquals("error.staffing.overAllocation", ex.getMessageKey());
        // 隣接（前end_dateの翌日=次start_date）は重複なし＝許可
        AllocationPlan b = save(percent(50), LocalDate.of(2026, 9, 16), SEP30);
        allocationService.confirm(b.getId());
        assertEquals(STATUS_CONFIRMED, allocationMapper.selectById(b.getId()).getStatus());
    }

    @Test
    void openEndの配置は計画window末までとみなされ重複判定される() {
        AllocationPlan a = save(percent(60), SEPT, null);
        allocationService.confirm(a.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(plan(percent(50), SEPT, SEP30)));
        assertEquals("error.staffing.overAllocation", ex.getMessageKey());
    }

    @Test
    void 破棄済み配置は重複判定の対象外になる() {
        AllocationPlan a = save(percent(60), SEPT, SEP30);
        allocationService.confirm(a.getId());
        allocationService.discard(a.getId());
        AllocationPlan b = save(percent(50), SEPT, SEP30);
        allocationService.confirm(b.getId());
        assertEquals(STATUS_CONFIRMED, allocationMapper.selectById(b.getId()).getStatus());
        assertEquals(STATUS_DISCARDED, allocationMapper.selectById(a.getId()).getStatus());
    }

    // ---------------------------------------------------------------
    // 例外承認（R2.2）
    // ---------------------------------------------------------------

    @Test
    void 例外理由がなければ過配賦を拒否する() {
        AllocationPlan a = save(percent(60), SEPT, SEP30);
        allocationService.confirm(a.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(plan(percent(50), SEPT, SEP30)));
        assertEquals("error.staffing.overAllocation", ex.getMessageKey());
    }

    @Test
    void 例外理由と承認で60パーセントプラス50パーセントを確定できる() {
        AllocationPlan a = save(percent(60), SEPT, SEP30);
        allocationService.confirm(a.getId());
        AllocationPlan draft = plan(percent(50), SEPT, SEP30);
        draft.setExceptionReason("特例: 短期集中対応");
        AllocationPlan saved = allocationService.saveDraft(draft);
        assertEquals(STATUS_DRAFT, saved.getStatus());
        assertNotNull(saved.getApprovalRequestId());
        ApprovalRequest approval = approvalRequestMapper.selectById(saved.getApprovalRequestId());
        assertEquals("staffing.overallocation", approval.getRequestType());

        // 承認前の確定は拒否される
        BusinessException before = assertThrows(BusinessException.class,
                () -> allocationService.confirm(saved.getId()));
        assertEquals("error.staffing.exceptionNotApproved", before.getMessageKey());

        // 承認後の確定は許可される
        authenticate(approverUserId, "管理者");
        approvalEngineService.approve(approval.getId(), approverUserId, "例外承認");
        authenticate(applicantUserId, "管理者");
        AllocationPlan confirmed = allocationService.confirm(saved.getId());
        assertEquals(STATUS_CONFIRMED, confirmed.getStatus());
    }

    @Test
    void 例外承認が却下された場合は確定できない() {
        AllocationPlan a = save(percent(60), SEPT, SEP30);
        allocationService.confirm(a.getId());
        AllocationPlan draft = plan(percent(50), SEPT, SEP30);
        draft.setExceptionReason("特例: 却下検証");
        AllocationPlan saved = allocationService.saveDraft(draft);
        authenticate(approverUserId, "管理者");
        approvalEngineService.reject(saved.getApprovalRequestId(), approverUserId, "却下");
        authenticate(applicantUserId, "管理者");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.confirm(saved.getId()));
        assertEquals("error.staffing.exceptionNotApproved", ex.getMessageKey());
    }

    // ---------------------------------------------------------------
    // 状態機械・競合（design §5.4）
    // ---------------------------------------------------------------

    @Test
    void 確定済みへの再確定と楽観ロック競合を拒否する() {
        AllocationPlan a = save(percent(50), SEPT, SEP30);
        allocationService.confirm(a.getId());
        // 状態CAS: 確定済みへの再確定は拒否
        BusinessException transition = assertThrows(BusinessException.class,
                () -> allocationService.confirm(a.getId()));
        assertEquals("error.staffing.invalidTransition", transition.getMessageKey());
        // version CAS: 別操作でversionが進んでいた下書きの上書きは拒否
        AllocationPlan draft = save(percent(40), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        jdbcTemplate.update("UPDATE t_allocation_plan SET version = 99 WHERE id = ?", draft.getId());
        AllocationPlan stale = allocationMapper.selectById(draft.getId());
        stale.setAllocationPercent(new BigDecimal("45"));
        stale.setVersion(0);
        BusinessException lock = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(stale));
        assertEquals("error.common.optimisticLock", lock.getMessageKey());
    }

    @Test
    void reviseは旧区間を破棄して新区間を確定する() {
        AllocationPlan a = save(percent(60), SEPT, LocalDate.of(2026, 9, 15));
        allocationService.confirm(a.getId());
        AllocationPlan b = save(percent(50), LocalDate.of(2026, 9, 16), SEP30);
        allocationService.confirm(b.getId());

        // 新区間(50% 9/16-9/30)はBと重なるが100%なので許可され、旧区間Aは破棄される
        AllocationPlan revised = allocationService.revise(a.getId(), plan(percent(50), LocalDate.of(2026, 9, 16), SEP30));
        assertEquals(STATUS_CONFIRMED, revised.getStatus());
        assertEquals(STATUS_DISCARDED, allocationMapper.selectById(a.getId()).getStatus());
        assertEquals(LocalDate.of(2026, 9, 16), revised.getStartDate());
    }

    @Test
    void reviseの失敗時は変更前の区間へ戻る() {
        // REQUIRES_NEWなtransactionで準備と失敗ケースを実行し、rollback後に実DB状態で検証する。
        // 外側transaction（@Transactional）からはuncommitted行が見えないため、
        // 準備も同じREQUIRES_NEW内で完結させる。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long aId = tx.execute(s -> {
            String suffix = String.valueOf(System.nanoTime());
            jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                    + "VALUES (?, '正社員', 'Bench')", "T075rv-eng-" + suffix);
            long engId = jdbcTemplate.queryForObject(
                    "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T075rv-eng-" + suffix);
            jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T075rv-" + suffix);
            long custId = jdbcTemplate.queryForObject(
                    "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T075rv-" + suffix);
            jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                    + "VALUES (?, ?, '募集中')", "T075rv-prj-" + suffix, custId);
            long prjId = jdbcTemplate.queryForObject(
                    "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T075rv-prj-" + suffix);
            ProjectPosition position = new ProjectPosition();
            position.setProjectId(prjId);
            position.setPositionNo("P1");
            position.setRoleName("Javaエンジニア");
            position.setRequiredCount(1);
            position.setAllocationPercent(new BigDecimal("100"));
            positionMapper.insert(position);
            AllocationPlan a = planFor(engId, position.getId(), percent(60), SEPT, LocalDate.of(2026, 9, 15));
            AllocationPlan confirmedA = allocationService.confirm(allocationService.saveDraft(a).getId());
            AllocationPlan b = planFor(engId, position.getId(), percent(50), LocalDate.of(2026, 9, 16), SEP30);
            allocationService.confirm(allocationService.saveDraft(b).getId());
            return confirmedA.getId();
        });
        BusinessException thrown = null;
        try {
            tx.executeWithoutResult(s -> {
                AllocationPlan overPlan = allocationMapper.selectById(aId);
                overPlan.setStartDate(LocalDate.of(2026, 9, 16));
                overPlan.setEndDate(SEP30);
                overPlan.setAllocationPercent(percent(60));
                allocationService.revise(aId, overPlan);
            });
        } catch (BusinessException e) {
            thrown = e;
        }
        assertNotNull(thrown);
        assertEquals("error.staffing.overAllocation", thrown.getMessageKey());

        // 失敗時は旧区間（確定）が維持され、破棄された行が残らない
        AllocationPlan a = allocationMapper.selectById(aId);
        assertEquals(STATUS_CONFIRMED, a.getStatus());
        assertEquals(0, new BigDecimal("60").compareTo(a.getAllocationPercent()));
        assertEquals(LocalDate.of(2026, 9, 1), a.getStartDate());
        assertEquals(0, allocationMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AllocationPlan>()
                        .eq(AllocationPlan::getEngineerId, a.getEngineerId())
                        .eq(AllocationPlan::getStatus, STATUS_DISCARDED)));
    }

    @Test
    void 実契約由来の配置は直接変更できない() {
        AllocationPlan a = save(percent(50), SEPT, SEP30);
        allocationService.confirm(a.getId());
        allocationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AllocationPlan>()
                .set(AllocationPlan::getSourceContractId, 999999L)
                .eq(AllocationPlan::getId, a.getId()));
        BusinessException ex = assertThrows(BusinessException.class, () -> allocationService.discard(a.getId()));
        assertEquals("error.staffing.actualManagedByContract", ex.getMessageKey());
    }

    // ---------------------------------------------------------------
    // 種別・境界（design §5.1/§5.2）
    // ---------------------------------------------------------------

    @Test
    void 社内待機はpositionIdなし案件はpositionId必須() {
        AllocationPlan internal = plan(percent(50), SEPT, SEP30);
        internal.setAllocationType(TYPE_INTERNAL);
        internal.setPositionId(null);
        AllocationPlan saved = allocationService.saveDraft(internal);
        assertEquals(STATUS_DRAFT, saved.getStatus());

        AllocationPlan bench = plan(percent(50), SEPT, SEP30);
        bench.setAllocationType(TYPE_BENCH);
        bench.setPositionId(null);
        allocationService.saveDraft(bench);

        AllocationPlan projectWithoutPosition = plan(percent(50), SEPT, SEP30);
        projectWithoutPosition.setPositionId(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(projectWithoutPosition));
        assertEquals("error.staffing.positionRequired", ex.getMessageKey());

        AllocationPlan internalWithPosition = plan(percent(50), SEPT, SEP30);
        internalWithPosition.setAllocationType(TYPE_INTERNAL);
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(internalWithPosition));
        assertEquals("error.staffing.noPositionForInternal", ex2.getMessageKey());
    }

    @Test
    void 計画window24か月を超える要求は拒否される() {
        LocalDate beyond = LocalDate.now().plusMonths(24).plusDays(1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(plan(percent(50), beyond, beyond)));
        assertEquals("error.staffing.horizonExceeded", ex.getMessageKey());
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(plan(percent(50), SEPT, beyond)));
        assertEquals("error.staffing.horizonExceeded", ex2.getMessageKey());
        // open end（end_date NULL）はwindow末まで
        AllocationPlan openEnd = save(percent(50), SEPT, null);
        allocationService.confirm(openEnd.getId());
        assertEquals(STATUS_CONFIRMED, allocationMapper.selectById(openEnd.getId()).getStatus());
    }

    @Test
    void 期間が逆転する配置は拒否される() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> allocationService.saveDraft(plan(percent(50), SEP30, SEPT)));
        assertEquals("error.staffing.invalidPeriod", ex.getMessageKey());
    }

    // ---------------------------------------------------------------
    // ヘルパー
    // ---------------------------------------------------------------

    private AllocationPlan save(BigDecimal percent, LocalDate start, LocalDate end) {
        return allocationService.saveDraft(plan(percent, start, end));
    }

    /** 指定の要員/ポジションで配置計画を作る（REQUIRES_NEW内でfixtureを完結させる用）。 */
    private AllocationPlan planFor(long engineerId, long positionId, BigDecimal percent,
                                   LocalDate start, LocalDate end) {
        AllocationPlan p = new AllocationPlan();
        p.setEngineerId(engineerId);
        p.setPositionId(positionId);
        p.setAllocationType(TYPE_PROJECT);
        p.setStartDate(start);
        p.setEndDate(end);
        p.setAllocationPercent(percent);
        return p;
    }

    private AllocationPlan plan(BigDecimal percent, LocalDate start, LocalDate end) {
        AllocationPlan p = new AllocationPlan();
        p.setEngineerId(engineerId);
        p.setPositionId(positionId);
        p.setAllocationType(TYPE_PROJECT);
        p.setStartDate(start);
        p.setEndDate(end);
        p.setAllocationPercent(percent);
        return p;
    }

    private BigDecimal percent(int value) {
        return new BigDecimal(value);
    }

    private long insertUser(String prefix) {
        SysUser user = SysUser.builder()
                .username(prefix + "-" + System.nanoTime())
                .password("x")
                .realName(prefix)
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private void insertRoute(String requestType, List<List<Long>> steps) {
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(requestType).organizationId(null)
                .minAmount(null).maxAmount(null).versionNo(1)
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1).build();
        approvalRouteMapper.insert(route);
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            for (Long approverId : steps.get(i)) {
                ApprovalRouteStep step = ApprovalRouteStep.builder()
                        .routeId(route.getId()).stepNo(stepNo).parallelGroup(stepNo)
                        .approverType("USER").approverValue(String.valueOf(approverId)).build();
                approvalRouteStepMapper.insert(step);
            }
        }
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
