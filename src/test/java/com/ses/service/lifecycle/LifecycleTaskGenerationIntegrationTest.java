package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.LifecycleTask;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.epoch.AuthorizationEpochFence;
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

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * U8（NF01細粒度epoch計画 §2.2・§4.2・§6.2・§7.5 Demo (1)〜(3)、W3-16b）: 実H2で、案件の保留・取消、タスクの担当変更・免除が
 * タスク世代を1つずつ進め、再開と同じ担当への再保存では進めないこと、同じtransactionの重複は1回になること、上限では409で
 * 止めることを確かめる。退社完了は、連携ユーザーのactorと要員のengineerを入れ子の書込み（停止・所属終了・紐付け解除・
 * 担当営業の解除）と合わせて1回ずつ進め、解除日・所属の終了日を退社基準日にする。
 *
 * <p>各テストの行はクラスの {@code @Transactional} でrollbackする（テストTXの外でcommitしない）。lockの順序・待ち・1213の
 * 有無はH2では証明しない（計画 RR-12）。実MySQLでは ResignationCompletionLockOrderMySqlTest（M6）が確かめる。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleTaskGenerationIntegrationTest {

    private static final String TENANT = "default";

    @Autowired private LifecycleTemplateService templateService;
    @Autowired private LifecycleCaseService caseService;
    @Autowired private LifecycleTaskService taskService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private EngineerSalesMapper engineerSalesMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private OrganizationUnitMapper organizationUnitMapper;
    @Autowired private LifecycleTaskMapper taskMapper;
    @Autowired private ApprovalRequestMapper approvalRequestMapper;
    @Autowired private com.ses.mapper.LifecycleLockMapper lifecycleLockMapper;
    @Autowired private JdbcTemplate jdbc;

    private SysUser admin;
    private SysUser hr;
    private Engineer engineer;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        long suffix = System.nanoTime();
        admin = insertUser("w316b_admin_" + suffix, "管理者");
        hr = insertUser("w316b_hr_" + suffix, "HR");
        LoginUser principal = new LoginUser(admin, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        engineer = Engineer.builder().tenantId(TENANT).legalEntityId(1L).fullName("W3-16b要員-" + suffix)
                .status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(engineer);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void lock文の後の読み直しはMyBatisのsession_cacheではなく現在の行を読む() {
        // W3-16b M6で判明: lock前と同じ照会をlock後に繰り返すと session cache がlock前の結果を返していた。
        // ここではMyBatisを通さない削除（他transactionのcommitの代わり）の後に、lock文 → 読み直しで現在の行を読むことを確かめる。
        long suffix = System.nanoTime();
        SysUser linked = insertUser("w316b_cache_" + suffix, "要員");
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(TENANT);
        link.setEngineerId(engineer.getId());
        link.setSysUserId(linked.getId());
        engineerAccountLinkMapper.insert(link);
        assertEquals(link.getId(), engineerAccountLinkMapper.selectByEngineerIdAndLinkTenant(engineer.getId(), TENANT).getId());
        jdbc.update("DELETE FROM t_engineer_account_link WHERE id = ?", link.getId());

        engineerMapper.selectScopeAnchorForUpdate(engineer.getId());
        assertEquals(null, engineerAccountLinkMapper.selectByEngineerIdAndLinkTenant(engineer.getId(), TENANT),
                "要員行のlock文の後は現在の行（削除済み）を読む");

        engineerAccountLinkMapper.insert(link);
        assertEquals(link.getId(), engineerAccountLinkMapper.selectByEngineerIdAndLinkTenant(engineer.getId(), TENANT).getId());
        jdbc.update("DELETE FROM t_engineer_account_link WHERE id = ?", link.getId());
        lifecycleLockMapper.lockEngineer(engineer.getId(), TENANT, true);
        assertEquals(null, engineerAccountLinkMapper.selectByEngineerIdAndLinkTenant(engineer.getId(), TENANT),
                "lock planのlock文の後も現在の行を読む（退社完了の再discovery）");
    }

    @Test
    void 保留は終端でないタスクの世代だけを1つ進め再開では戻さない() {
        List<LifecycleTask> tasks = createJoinCaseTasks();
        LifecycleTask done = tasks.get(0);
        taskService.completeTask(done.getId(), admin.getId(), null);
        Long caseId = done.getCaseId();

        caseService.holdCase(caseId, admin.getId(), "保留");

        assertEquals(0L, generation(done.getId()), "完了済みのタスクは進めない");
        for (LifecycleTask open : tasks.subList(1, tasks.size())) {
            assertEquals(1L, generation(open.getId()), "終端でないタスクは1つ進める");
        }

        caseService.resumeCase(caseId, admin.getId());

        for (LifecycleTask open : tasks.subList(1, tasks.size())) {
            assertEquals(1L, generation(open.getId()), "再開は世代を戻さず進めもしない");
        }
    }

    @Test
    void 取消は取り消したタスクの世代だけを1つ進める() {
        List<LifecycleTask> tasks = createJoinCaseTasks();
        LifecycleTask done = tasks.get(0);
        taskService.completeTask(done.getId(), admin.getId(), null);

        caseService.cancelCase(done.getCaseId(), admin.getId(), "取消");

        assertEquals(0L, generation(done.getId()));
        for (LifecycleTask cancelled : tasks.subList(1, tasks.size())) {
            assertEquals("CANCELLED", taskMapper.selectById(cancelled.getId()).getStatus());
            assertEquals(1L, generation(cancelled.getId()));
        }
    }

    @Test
    void 担当変更は世代を1つ進め同じtransactionで再度変更しても1回だけで別のタスクは変えない() {
        List<LifecycleTask> tasks = createJoinCaseTasks();
        LifecycleTask target = tasks.get(0);
        LifecycleTask other = tasks.get(1);

        // 作成時の担当はrole HR（HRの利用者へ解決され得る）。管理者へ変え（role も変わる）、続けてHRの利用者へ戻す。
        // どちらも担当の変化だが、同じtransactionの同じタスクは1回だけ進める（E-4）。
        taskService.reassignTask(target.getId(), admin.getId(), admin.getId(), "担当変更");
        taskService.reassignTask(target.getId(), hr.getId(), admin.getId(), "再変更");

        assertEquals(1L, generation(target.getId()), "同じtransactionの同じタスクは1回だけ（E-4）");
        assertEquals(0L, generation(other.getId()), "別のタスクは変えない（細粒度）");
        assertEquals(hr.getId(), taskMapper.selectById(target.getId()).getAssigneeUserId());
    }

    @Test
    void 同じ担当への再保存では世代を進めない() {
        List<LifecycleTask> tasks = createJoinCaseTasks();
        LifecycleTask target = tasks.get(0);
        // 担当を直接HRにしておく（同じtransactionでfenceが増分済みのanchorを作らないため、serviceを通さない）。
        jdbc.update("UPDATE t_lifecycle_task SET assignee_user_id = ?, assignee_role = ? WHERE id = ?",
                hr.getId(), hr.getRole(), target.getId());

        // 同じ利用者・同じroleへの担当変更はno-op（E-4）。
        taskService.reassignTask(target.getId(), hr.getId(), admin.getId(), "同じ担当");

        assertEquals(0L, generation(target.getId()));
    }

    @Test
    void 免除は世代を1つ進める() {
        List<LifecycleTask> tasks = createJoinCaseTasks();
        LifecycleTask target = tasks.get(0);
        ApprovalRequest approval = ApprovalRequest.builder().tenantId(TENANT).requestNo("AR-W316B-" + System.nanoTime())
                .requestType("LIFECYCLE_EXCEPTION").targetType("LIFECYCLE_TASK").targetId(target.getId())
                .targetVersion(0L).applicantId(admin.getId())
                .payloadJson("{\"reason\":\"検証\",\"riskOwner\":\"HR\",\"remedyDeadline\":\""
                        + LocalDate.now().plusMonths(1) + "\"}")
                .routeSnapshotJson("[]").status("APPROVED").version(1).build();
        approvalRequestMapper.insert(approval);

        taskService.waiveTask(target.getId(), admin.getId(), approval.getId(), "承認済み例外");

        assertEquals("WAIVED", taskMapper.selectById(target.getId()).getStatus());
        assertEquals(1L, generation(target.getId()));
        assertEquals(0L, generation(tasks.get(1).getId()));
    }

    /**
     * RB-POSTLAUNCH review P3-8: 社内専用（本人非公開）のタスクは要員ロールの利用者へ割り当てない。割り当てると期日の通知や
     * 確認待ちの通知でタスク名・案件titleが要員側へ渡る。本人公開のタスクは要員へ、社内専用のタスクも要員以外へは割り当てられる。
     */
    @Test
    void 社内専用のタスクは要員へ担当変更できず本人公開のタスクと要員以外への変更はできる() {
        List<LifecycleTask> tasks = createJoinCaseTasks();
        LifecycleTask internalTask = tasks.get(0);
        LifecycleTask visibleTask = tasks.get(1);
        jdbc.update("UPDATE t_lifecycle_task SET is_engineer_visible = 0 WHERE id = ?", internalTask.getId());
        jdbc.update("UPDATE t_lifecycle_task SET is_engineer_visible = 1 WHERE id = ?", visibleTask.getId());
        SysUser engineerUser = insertUser("w316b_eng_assignee_" + System.nanoTime(), "要員");

        taskService.reassignTask(visibleTask.getId(), engineerUser.getId(), admin.getId(), "本人公開");
        assertEquals(engineerUser.getId(), taskMapper.selectById(visibleTask.getId()).getAssigneeUserId());
        taskService.reassignTask(internalTask.getId(), admin.getId(), admin.getId(), "社内の担当");
        assertEquals(admin.getId(), taskMapper.selectById(internalTask.getId()).getAssigneeUserId());
        long generationBefore = generation(internalTask.getId());

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> taskService.reassignTask(internalTask.getId(), engineerUser.getId(), admin.getId(), "要員へ"));

        assertEquals(400, rejected.getCode());
        assertEquals("error.lifecycle.internalTaskEngineerAssignee", rejected.getMessageKey());
        LifecycleTask unchanged = taskMapper.selectById(internalTask.getId());
        assertEquals(admin.getId(), unchanged.getAssigneeUserId(), "担当を変えない");
        assertEquals(generationBefore, generation(internalTask.getId()), "世代を進めない");
    }

    @Test
    void タスク世代が上限なら担当変更は409で止まり値を変えない() {
        List<LifecycleTask> tasks = createJoinCaseTasks();
        LifecycleTask target = tasks.get(0);
        jdbc.update("UPDATE t_lifecycle_task SET task_generation = ? WHERE id = ?", Long.MAX_VALUE, target.getId());

        // 管理者への変更は担当（利用者・role）が必ず変わる（作成時はrole HR）。
        BusinessException ex = assertThrows(BusinessException.class,
                () -> taskService.reassignTask(target.getId(), admin.getId(), admin.getId(), "上限"));

        assertEquals(409, ex.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, ex.getMessageKey());
        assertEquals(Long.MAX_VALUE, generation(target.getId()));
    }

    @Test
    void 退社完了は入れ子の書込みと合わせて連携ユーザーのactorと要員のengineerを1回ずつ進め基準日で閉じる() {
        long suffix = System.nanoTime();
        SysUser linked = insertUser("w316b_linked_" + suffix, "要員");
        SysUser sales = insertUser("w316b_sales_" + suffix, "営業");
        OrganizationUnit org = OrganizationUnit.builder().tenantId(1L).legalEntityId(1L).code("W316B-" + suffix)
                .name("W3-16b組織-" + suffix).type("DEPARTMENT").status("ACTIVE")
                .validFrom(LocalDate.now().minusYears(1)).build();
        organizationUnitMapper.insert(org);
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(TENANT);
        link.setEngineerId(engineer.getId());
        link.setSysUserId(linked.getId());
        engineerAccountLinkMapper.insert(link);
        engineerSalesMapper.insert(EngineerSales.builder().engineerId(engineer.getId()).salesUserId(sales.getId())
                .primaryFlag(1).assignedAt(LocalDate.now().minusMonths(6)).build());
        userOrganizationMapper.insert(UserOrganization.builder().tenantId(TENANT).userId(linked.getId())
                .organizationId(org.getId()).primaryFlag(1).validFrom(LocalDate.now().minusYears(1)).version(0).build());
        LocalDate anchorDate = LocalDate.now().minusDays(2);
        LifecycleCaseDto resignation = caseService.createCase(admin.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId()).lifecycleType("RESIGNATION").templateId(resignationTemplate().getId())
                .anchorDate(anchorDate).title("W3-16b退社完了").build());
        for (LifecycleTask task : taskMapper.selectByCaseId(resignation.getId())) {
            taskService.completeTask(task.getId(), admin.getId(), null);
        }
        long actorBefore = actorEpoch(linked.getId());
        long engineerBefore = engineerEpoch(engineer.getId());
        long operatorBefore = actorEpoch(admin.getId());

        caseService.completeCase(resignation.getId(), admin.getId());

        assertEquals(0, sysUserMapper.selectById(linked.getId()).getStatus());
        assertEquals(actorBefore + 1, actorEpoch(linked.getId()), "停止と所属の終了で合わせて1回");
        assertEquals(engineerBefore + 1, engineerEpoch(engineer.getId()),
                "所属の終了・紐付け解除・担当営業の解除で合わせて1回");
        assertEquals(operatorBefore, actorEpoch(admin.getId()), "操作者（共有lockだけ）の世代は変えない");
        for (EngineerSales row : engineerSalesMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerSales>()
                        .eq(EngineerSales::getEngineerId, engineer.getId()))) {
            assertEquals(anchorDate, row.getReleasedAt(), "担当営業の解除日は退社基準日");
        }
        for (UserOrganization row : userOrganizationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserOrganization>()
                        .eq(UserOrganization::getUserId, linked.getId()))) {
            assertEquals(anchorDate, row.getValidTo(), "所属の終了日は退社基準日");
        }
        assertEquals(null, engineerAccountLinkMapper.selectByEngineerId(engineer.getId()), "紐付けは解除される");
        assertEquals("Bench", engineerMapper.selectById(engineer.getId()).getStatus());
    }

    // ===== fixture =====

    /** 先行依存の無い3タスク（作成時にすべて IN_PROGRESS）の入社案件を作り、ID昇順のタスクを返す。 */
    private List<LifecycleTask> createJoinCaseTasks() {
        LifecycleTemplateDto template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("JOIN").name("W3-16b世代検証-" + System.nanoTime())
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(templateTask("W316B_A", 1), templateTask("W316B_B", 2), templateTask("W316B_C", 3)))
                .build(), admin.getId());
        LifecycleCaseDto created = caseService.createCase(admin.getId(), CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId()).lifecycleType("JOIN").templateId(template.getId())
                .anchorDate(LocalDate.now()).title("W3-16b世代検証").build());
        List<LifecycleTask> tasks = taskMapper.selectByCaseId(created.getId()).stream()
                .sorted(Comparator.comparing(LifecycleTask::getId)).toList();
        assertEquals(3, tasks.size());
        for (LifecycleTask task : tasks) {
            assertEquals("IN_PROGRESS", task.getStatus());
            assertEquals(0L, generation(task.getId()));
        }
        return tasks;
    }

    private LifecycleTemplateDto resignationTemplate() {
        return templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("RESIGNATION").name("W3-16b退社-" + System.nanoTime())
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(templateTask("RESIGN_ASSET_RETURN", 1), templateTask("RESIGN_DOC_RETENTION", 2)))
                .build(), admin.getId());
    }

    private static LifecycleTemplateTaskDto templateTask(String code, int order) {
        return LifecycleTemplateTaskDto.builder().taskCode(code).taskName(code).assigneeRule("ROLE")
                .assigneeRuleValue("HR").sortOrder(order).isMandatory(1).isBlocking(1).build();
    }

    private SysUser insertUser(String username, String role) {
        SysUser user = SysUser.builder().tenantId(TENANT).username(username).password("pass")
                .realName("W3-16b " + role).role(role).status(1).build();
        sysUserMapper.insert(user);
        return user;
    }

    private long generation(Long taskId) {
        return jdbc.queryForObject("SELECT task_generation FROM t_lifecycle_task WHERE id = ?", Long.class, taskId);
    }

    private long actorEpoch(Long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }

    private long engineerEpoch(Long engineerId) {
        return jdbc.queryForObject("SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?", Long.class, engineerId);
    }
}
