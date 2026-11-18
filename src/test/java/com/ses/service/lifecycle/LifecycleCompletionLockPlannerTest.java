package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U8（NF01細粒度epoch計画 §3.4「completeCase」、W3-16b）: 退社完了のlock planが、自動処理と失効要求登録の書く行を
 * rank順（3 S{操作者}・X{連携ユーザー} → 4 → 5 → 7 → 8 → 9 → 10 → 14）に並べ、操作者自身の退社完了を拒否することを固定する。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleCompletionLockPlannerTest {

    private static final String TENANT = "tenant-a";
    private static final long OPERATOR_ID = 9L;
    private static final long LINKED_USER_ID = 4L;
    private static final long CASE_ID = 10L;
    private static final long ENGINEER_ID = 20L;

    @Mock private LifecycleTaskMapper taskMapper;
    @Mock private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Mock private UserOrganizationMapper userOrganizationMapper;
    @Mock private EngineerSalesMapper engineerSalesMapper;
    @Mock private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @InjectMocks
    private LifecycleCompletionLockPlanner planner;

    @Test
    void 退社完了のplanは連携ユーザーを要員より先にし自動処理と失効要求登録の書く行をrank順に並べる() {
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of(task(12L), task(11L)));
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(link(61L));
        // 1回目: 本人の所属行（一括終了の対象）、2回目: 本人を上長とする他人の所属行（上長解除の対象）。
        when(userOrganizationMapper.selectList(any())).thenReturn(List.of(assignment(52L), assignment(51L)),
                List.of(assignment(55L)));
        when(engineerSalesMapper.selectList(any())).thenReturn(List.of(sales(71L)));
        when(externalAccountReferenceMapper.selectUnrevokedForEngineer(TENANT, ENGINEER_ID))
                .thenReturn(List.of(account(92L), account(91L)));

        LifecycleLockPlan plan = planner.plan(resignation(), OPERATOR_ID, TENANT);

        assertEquals(List.of(
                LockKey.exclusive(Rank.SYS_USER, TENANT, LINKED_USER_ID),
                LockKey.shared(Rank.SYS_USER, TENANT, OPERATOR_ID),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, TENANT, CASE_ID),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, TENANT, 11L),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, TENANT, 12L),
                LockKey.exclusive(Rank.ENGINEER, TENANT, ENGINEER_ID),
                LockKey.exclusive(Rank.USER_ORGANIZATION, TENANT, 51L),
                LockKey.exclusive(Rank.USER_ORGANIZATION, TENANT, 52L),
                LockKey.exclusive(Rank.USER_ORGANIZATION, TENANT, 55L),
                LockKey.exclusive(Rank.ENGINEER_ACCOUNT_LINK, TENANT, 61L),
                LockKey.exclusive(Rank.ENGINEER_SALES, "", 71L),
                LockKey.exclusive(Rank.EXTERNAL_ACCOUNT_REFERENCE, TENANT, 91L),
                LockKey.exclusive(Rank.EXTERNAL_ACCOUNT_REFERENCE, TENANT, 92L)), plan.locks());
    }

    @Test
    void 連携の無い退社案件は要員と担当営業と外部アカウントだけを足す() {
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of());
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(null);
        when(engineerSalesMapper.selectList(any())).thenReturn(List.of());
        when(externalAccountReferenceMapper.selectUnrevokedForEngineer(TENANT, ENGINEER_ID)).thenReturn(List.of());

        LifecycleLockPlan plan = planner.plan(resignation(), OPERATOR_ID, TENANT);

        assertEquals(List.of(
                LockKey.shared(Rank.SYS_USER, TENANT, OPERATOR_ID),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, TENANT, CASE_ID),
                LockKey.exclusive(Rank.ENGINEER, TENANT, ENGINEER_ID)), plan.locks());
        verifyNoInteractions(userOrganizationMapper);
    }

    @Test
    void 退社以外の案件は操作者と案件と配下タスクだけをlockする() {
        LifecycleCase join = resignation();
        join.setLifecycleType("JOIN");
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of(task(11L)));

        LifecycleLockPlan plan = planner.plan(join, OPERATOR_ID, TENANT);

        assertEquals(List.of(
                LockKey.shared(Rank.SYS_USER, TENANT, OPERATOR_ID),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, TENANT, CASE_ID),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, TENANT, 11L)), plan.locks());
        verifyNoInteractions(engineerAccountLinkMapper, userOrganizationMapper, engineerSalesMapper,
                externalAccountReferenceMapper);
    }

    @Test
    void 操作者自身が退社要員の連携ユーザーなら400で拒否し案内文言のkeyを返す() {
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of());
        EngineerAccountLink self = link(61L);
        self.setSysUserId(OPERATOR_ID);
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(self);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planner.plan(resignation(), OPERATOR_ID, TENANT));

        // 403は画面の共通処理が固定文言へ置き換え案内が出ないため、業務規則の拒否として400にする（review P3-2）。
        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.selfResignationCompletion", ex.getMessageKey());
        verifyNoInteractions(userOrganizationMapper, engineerSalesMapper, externalAccountReferenceMapper);
    }

    @Test
    void tenantを読めない所属行があれば409で止める() {
        when(taskMapper.selectByCaseIdAndTenant(CASE_ID, TENANT)).thenReturn(List.of());
        when(engineerAccountLinkMapper.selectByEngineerIdAndTenant(ENGINEER_ID, TENANT)).thenReturn(link(61L));
        UserOrganization broken = assignment(51L);
        broken.setTenantId(" ");
        when(userOrganizationMapper.selectList(any())).thenReturn(List.of(broken));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> planner.plan(resignation(), OPERATOR_ID, TENANT));

        assertEquals(409, ex.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, ex.getMessageKey());
    }

    private static LifecycleCase resignation() {
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(TENANT).lifecycleType("RESIGNATION")
                .engineerId(ENGINEER_ID).anchorDate(LocalDate.of(2026, 11, 30)).status("ACTIVE").build();
        lcCase.setId(CASE_ID);
        return lcCase;
    }

    private static LifecycleTask task(long id) {
        LifecycleTask task = LifecycleTask.builder().caseId(CASE_ID).status("COMPLETED").build();
        task.setId(id);
        return task;
    }

    private static EngineerAccountLink link(long id) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setId(id);
        link.setTenantId(TENANT);
        link.setEngineerId(ENGINEER_ID);
        link.setSysUserId(LINKED_USER_ID);
        return link;
    }

    private static UserOrganization assignment(long id) {
        UserOrganization row = UserOrganization.builder().tenantId(TENANT).userId(LINKED_USER_ID)
                .organizationId(1L).primaryFlag(1).validFrom(LocalDate.of(2020, 1, 1)).build();
        row.setId(id);
        return row;
    }

    private static EngineerSales sales(long id) {
        EngineerSales row = EngineerSales.builder().engineerId(ENGINEER_ID).salesUserId(7L).primaryFlag(1).build();
        row.setId(id);
        return row;
    }

    private static ExternalAccountReference account(long id) {
        ExternalAccountReference row = ExternalAccountReference.builder().tenantId(TENANT).legalEntityId(1L)
                .assigneeType("ENGINEER").assigneeId(ENGINEER_ID).status("ACTIVE").build();
        row.setId(id);
        return row;
    }
}
