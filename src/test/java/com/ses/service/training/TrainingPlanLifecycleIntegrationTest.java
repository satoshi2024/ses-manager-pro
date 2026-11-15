package com.ses.service.training;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.dto.accounting.AccountingReconciliationSummaryDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.MonthlyClosingService;
import com.ses.service.OrganizationService;
import com.ses.service.accounting.AccountingReconciliationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.expense.ExpenseRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 非PIIの計画・既存承認・経費・完了・所属異動・月次締めを一つのH2トランザクションで接続する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {"app.features.nf03.enabled=true", "app.features.nf03-certification-pii.enabled=false"})
class TrainingPlanLifecycleIntegrationTest {
    private static final LocalDate START = LocalDate.of(2026, 8, 3);
    private static final BigDecimal COST = new BigDecimal("12000");
    @Autowired private TrainingPlanService training;
    @Autowired private ExpenseRequestService expenses;
    @Autowired private OrganizationService organizations;
    @Autowired private MonthlyClosingService closing;
    @Autowired private SysUserMapper users;
    @Autowired private EngineerMapper engineers;
    @Autowired private EngineerAccountLinkMapper links;
    @Autowired private ApprovalRouteMapper routes;
    @Autowired private ApprovalRouteStepMapper steps;
    @Autowired private ApprovalRequestMapper approvals;
    @Autowired private ApprovalActionMapper actions;
    @Autowired private LearningPlanMapper plans;
    @Autowired private LearningPlanEventMapper events;
    @Autowired private TrainingCourseMapper courses;
    @Autowired private TrainingEnrollmentMapper enrollments;
    @Autowired private TrainingEnrollmentExpenseMapper relations;
    @Autowired private UserOrganizationMapper assignments;
    @Autowired private MonthlyAccountingDimensionMapper dimensions;
    @Autowired private EngineerAccountingHistoryMapper accountingHistory;

    // 外部freeeとの照合はこの非PII業務フローの対象外。計画・承認・費用・締め本体は実Beanを使う。
    @MockBean private AccountingReconciliationService reconciliation;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 学習計画完了後の異動履歴で八月九月の帰属を分離し締め済み月への費用登録を拒否する() {
        String tenant = "nf03-flow-" + UUID.randomUUID();
        AccountingTenantContextHolder.setTenantId(tenant);
        SysUser applicant = user(tenant, "要員");
        SysUser approver = user(tenant, "管理者");
        OrganizationUnit source = organization("異動元");
        OrganizationUnit target = organization("異動先");
        UserOrganization original = organizations.assignUser(UserOrganization.builder().tenantId(tenant)
                .userId(applicant.getId()).organizationId(source.getId()).primaryFlag(1)
                .validFrom(START.minusMonths(1)).version(0).build());
        Engineer engineer = Engineer.builder().tenantId(tenant).fullName("学習フロー検証要員")
                .employmentType("正社員").status("Bench").organizationId(source.getId()).build();
        engineers.insert(engineer);
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(tenant);
        link.setEngineerId(engineer.getId());
        link.setSysUserId(applicant.getId());
        links.insert(link);
        route(tenant, source.getId(), approver.getId());
        authenticate(applicant);

        LearningPlan draft = new LearningPlan();
        draft.setTenantId(tenant);
        draft.setEngineerId(engineer.getId());
        draft.setTitle("非PII研修計画");
        draft.setGoalDescription("設計レビュー手順を習得する");
        draft.setAttainmentCriteria("演習完了と担当者の確認");
        draft.setPlannedStartOn(START);
        draft.setPlannedEndOn(START.plusDays(2));
        draft.setPlannedCostJpy(COST);
        LearningPlan created = training.createDraft(draft, applicant.getId());
        LearningPlan submitted = training.submit(created.getId(), created.getVersion(), applicant.getId(), null);
        assertThat(submitted.getStatus()).isEqualTo(TrainingPlanService.PLAN_SUBMITTED);
        ExpenseRequest beforeApproval = expenses.getEntity(submitted.getExpenseRequestId());
        assertThat(beforeApproval.getStatus()).isEqualTo(ExpenseRequestService.STATUS_APPLIED);
        assertThat(beforeApproval.getAmount()).isEqualByComparingTo(COST);
        ApprovalRequest request = approvals.selectById(submitted.getApprovalRequestId());
        assertThat(request.getApplicantId()).isEqualTo(applicant.getId());
        assertThat(request.getTargetId()).isEqualTo(beforeApproval.getId());
        assertThat(request.getAmountSnapshot()).isEqualByComparingTo(COST);

        authenticate(approver);
        LearningPlan approved = training.approve(created.getId(), submitted.getVersion(), approver.getId(), "上長確認");
        assertThat(approved.getStatus()).isEqualTo(TrainingPlanService.PLAN_APPROVED);
        assertThat(approvals.selectById(request.getId()).getStatus()).isEqualTo("approved");
        assertThat(expenses.getEntity(beforeApproval.getId()).getStatus()).isEqualTo(ExpenseRequestService.STATUS_APPROVED);
        assertThat(actions.selectList(new LambdaQueryWrapper<ApprovalAction>().eq(ApprovalAction::getRequestId, request.getId())))
                .singleElement().satisfies(action -> {
                    assertThat(action.getApproverUserId()).isEqualTo(approver.getId()).isNotEqualTo(applicant.getId());
                    assertThat(action.getAction()).isEqualTo("APPROVE");
                });

        TrainingCourse course = new TrainingCourse();
        course.setTenantId(tenant);
        course.setProvider("社内研修");
        course.setName("設計演習");
        course.setCostJpy(COST);
        course.setActiveFlag(1);
        course.setVersion(0);
        courses.insert(course);
        TrainingEnrollment enrolled = training.enroll(created.getId(), approved.getVersion(), course.getId(), approver.getId());
        assertThat(relations.selectList(new LambdaQueryWrapper<TrainingEnrollmentExpense>()
                .eq(TrainingEnrollmentExpense::getEnrollmentId, enrolled.getId())))
                .singleElement().extracting(TrainingEnrollmentExpense::getExpenseRequestId).isEqualTo(beforeApproval.getId());
        TrainingEnrollment started = training.startEnrollment(enrolled.getId(), enrolled.getVersion(), approver.getId());
        training.completeEnrollment(enrolled.getId(), started.getVersion(), START.plusDays(2), new BigDecimal("90"), approver.getId());
        assertThat(enrollments.selectById(enrolled.getId()).getStatus()).isEqualTo(TrainingPlanService.ENROLLMENT_COMPLETED);
        assertThat(plans.selectById(created.getId()).getStatus()).isEqualTo(TrainingPlanService.PLAN_COMPLETED);

        organizations.transferUser(UserOrganization.builder().tenantId(tenant).userId(applicant.getId())
                .organizationId(target.getId()).primaryFlag(1).validFrom(LocalDate.of(2026, 9, 1)).version(0).build(),
                original.getVersion());
        // 現在値を異動先へ変えた上で、過去月は履歴のsourceを使用することを証明する。
        accountingHistory.insert(EngineerAccountingHistory.builder().engineerId(engineer.getId())
                .organizationId(source.getId()).organizationHistoryStatus("KNOWN").expectedUnitPrice(COST)
                .validFrom(START.minusMonths(1)).validTo(LocalDate.of(2026, 8, 31)).build());
        accountingHistory.insert(EngineerAccountingHistory.builder().engineerId(engineer.getId())
                .organizationId(target.getId()).organizationHistoryStatus("KNOWN").expectedUnitPrice(COST)
                .validFrom(LocalDate.of(2026, 9, 1)).build());
        engineers.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Engineer>()
                .eq(Engineer::getId, engineer.getId()).set(Engineer::getOrganizationId, target.getId()));
        assertThat(engineers.selectById(engineer.getId()).getOrganizationId()).isEqualTo(target.getId());
        assertThat(assignments.selectById(original.getId()).getValidTo()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(organizations.listUserOrganizations(applicant.getId(), START))
                .singleElement().extracting(UserOrganization::getOrganizationId).isEqualTo(source.getId());
        assertThat(organizations.listUserOrganizations(applicant.getId(), LocalDate.of(2026, 9, 1)))
                .singleElement().extracting(UserOrganization::getOrganizationId).isEqualTo(target.getId());
        assertThat(approvals.selectById(request.getId()).getOrganizationId()).isEqualTo(source.getId());

        when(reconciliation.reconcileMonth("2026-08")).thenReturn(new AccountingReconciliationSummaryDto());
        when(reconciliation.reconcileMonth("2026-09")).thenReturn(new AccountingReconciliationSummaryDto());
        closing.confirmClosing("2026-08", approver.getId(), "管理者");
        closing.confirmClosing("2026-09", approver.getId(), "管理者");
        verify(reconciliation).assertReconciledForClosing("2026-08");
        assertThat(closing.isClosed("2026-08")).isTrue();
        assertThat(dimensions.selectList(new LambdaQueryWrapper<MonthlyAccountingDimension>()
                .eq(MonthlyAccountingDimension::getSourceType, "bench-engineer")
                .eq(MonthlyAccountingDimension::getSourceId, engineer.getId())
                .eq(MonthlyAccountingDimension::getWorkMonth, START.withDayOfMonth(1))))
                .singleElement().extracting(MonthlyAccountingDimension::getOrganizationId).isEqualTo(source.getId());
        assertThat(dimensions.selectList(new LambdaQueryWrapper<MonthlyAccountingDimension>()
                .eq(MonthlyAccountingDimension::getSourceType, "bench-engineer")
                .eq(MonthlyAccountingDimension::getSourceId, engineer.getId())
                .eq(MonthlyAccountingDimension::getWorkMonth, LocalDate.of(2026, 9, 1))))
                .singleElement().extracting(MonthlyAccountingDimension::getOrganizationId).isEqualTo(target.getId());
        assertThat(plans.selectById(created.getId()).getPlannedCostJpy()).isEqualByComparingTo(COST);
        assertThat(expenses.getEntity(beforeApproval.getId()).getAmount()).isEqualByComparingTo(COST);
        assertThat(events.selectByPlanId(created.getId()))
                .extracting(LearningPlanEvent::getEventType)
                .contains("PLAN_CREATED", "PLAN_SUBMITTED", "PLAN_APPROVED", "ENROLLMENT_STARTED", "ENROLLMENT_COMPLETED");
        assertThatThrownBy(() -> expenses.createDraft(engineer.getId(), new ExpenseRequestService.ExpenseDraftCommand(
                START, ExpenseRequestService.CATEGORY_TRAINING, COST, null, null, "締め後の費用変更")))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo(400);
                    assertThat(failure.getMessage()).isEqualTo("error.closing.hardLocked");
                });
    }

    private SysUser user(String tenant, String role) {
        SysUser row = SysUser.builder().tenantId(tenant).username("nf03-" + UUID.randomUUID())
                .password("test-only").realName("学習検証").role(role).status(1).build();
        users.insert(row);
        return row;
    }

    private OrganizationUnit organization(String name) {
        OrganizationUnit row = OrganizationUnit.builder().tenantId(1L).code("NF03-" + UUID.randomUUID())
                .name(name).type("部門").validFrom(START.minusYears(1)).status("有効").version(0).build();
        organizations.save(row);
        return row;
    }

    private void route(String tenant, Long organizationId, Long approverId) {
        ApprovalRoute row = ApprovalRoute.builder().tenantId(tenant).requestType("expense.request")
                .organizationId(organizationId).minAmount(COST).maxAmount(COST).versionNo(1)
                .validFrom(START.minusYears(1)).activeFlag(1).build();
        routes.insert(row);
        steps.insert(ApprovalRouteStep.builder().routeId(row.getId()).stepNo(1).parallelGroup(1)
                .approverType("USER").approverValue(approverId.toString()).build());
    }

    private void authenticate(SysUser user) {
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
        AccountingTenantContextHolder.setTenantId(user.getTenantId());
    }
}
