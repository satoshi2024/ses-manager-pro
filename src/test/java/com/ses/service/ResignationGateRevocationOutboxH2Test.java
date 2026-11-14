package com.ses.service;

import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.provider.ExternalAccountProviderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.ses.service.ExternalAccountRevocationStates.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** U10: 実H2のCAS・履歴・全法人と再入社判定。各テストの行はrollbackで破棄する。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:revocation-outbox-h2;DB_CLOSE_DELAY=-1;MODE=MySQL")
@ActiveProfiles("test")
@Transactional
class ResignationGateRevocationOutboxH2Test {
    @Autowired ExternalAccountRevocationOutbox outbox;
    @Autowired ExternalAccountReferenceMapper references;
    @Autowired ExternalAccountSystemMapper systems;
    @Autowired EngineerMapper engineers;
    @Autowired LifecycleCaseMapper cases;
    @Autowired AssetEventMapper events;
    @Autowired AssetOffboardingWaiverMapper waivers;
    @Autowired ApprovalRequestMapper approvalRequests;
    @Autowired LifecycleTaskMapper tasks;
    @Autowired SysUserMapper users;
    @Autowired AssetOffboardingService clearance;
    @Autowired ExternalAccountRevokeConfirmationService confirmations;
    @Autowired com.ses.controller.api.ExternalAccountApiController api;
    @Autowired ExternalAccountService accountService;
    @MockBean ExternalAccountProviderClient provider;
    @MockBean com.ses.service.security.LegalEntityContextService legalContext;
    String tenant;
    Engineer engineer;
    ExternalAccountSystem system;

    @BeforeEach void seed() {
        tenant = "outbox-h2-" + UUID.randomUUID();
        AccountingTenantContextHolder.setTenantId(tenant);
        lenient().when(legalContext.requireTenantId()).thenReturn(tenant);
        lenient().when(legalContext.requireCurrentLegalEntityId()).thenReturn(1L);
        engineer = Engineer.builder().tenantId(tenant).legalEntityId(1L).fullName("失効要求検証").employmentType("正社員").build();
        engineers.insert(engineer);
        system = ExternalAccountSystem.builder().systemCode("OUTBOX-" + UUID.randomUUID()).systemName("検証用").systemType("IDP").build();
        systems.insert(system);
    }
    @AfterEach void clear() { AccountingTenantContextHolder.clear(); }

    @Test void 登録可能な全法人のアカウントはゲートを妨げず同じtransactionで要求を登録する() {
        ExternalAccountReference a = account(1L), b = account(2L);
        var before = clearance.checkOffboardingClearance(engineer.getId(), null, null);
        assertThat(before.getUnrevokedAccountCount()).isEqualTo(2);
        assertThat(before.getUnregisterableAccountCount()).isZero();
        assertThat(before.isClearancePassed()).isTrue();
        LifecycleCase resignation = lifecycle("RESIGNATION", "ACTIVE");
        outbox.enqueueForResignation(resignation, null);
        for (ExternalAccountReference row : new ExternalAccountReference[]{a, b}) {
            ExternalAccountReference stored = references.selectById(row.getId());
            assertThat(stored.getExternalSyncStatus()).isEqualTo(REQUESTED);
            assertThat(stored.getLegalEntityId()).isEqualTo(row.getLegalEntityId());
            assertThat(stored.getIdempotencyKey()).isEqualTo("lifecycle-resign:" + resignation.getId() + ":" + row.getId() + ":g1");
            assertThat(events.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetEvent>()
                    .eq(AssetEvent::getReferenceType, "EXTERNAL_ACCOUNT_REFERENCE").eq(AssetEvent::getReferenceId, row.getId())))
                    .singleElement().satisfies(event -> assertThat(event.getActorType()).isEqualTo("SYSTEM"));
        }
        verifyNoInteractions(provider);
    }

    @Test void 法人不明だけが登録不能となりゲートを妨げる() {
        account(1L); account(null);
        var result = clearance.checkOffboardingClearance(engineer.getId(), null, null);
        assertThat(result.getUnrevokedAccountCount()).isEqualTo(2);
        assertThat(result.getUnregisterableAccountCount()).isEqualTo(1);
        assertThat(result.isClearancePassed()).isFalse();
        assertThat(result.getBlockingItems()).allSatisfy(text -> assertThat(text).doesNotContain("private-identifier"));
    }

    @Test void 永続免除が存在しても法人不明の照合結果を退社可能と返さない() {
        account(null);
        LifecycleCase resignation = lifecycle("RESIGNATION", "ACTIVE");
        LifecycleTask task = LifecycleTask.builder().caseId(resignation.getId()).taskCode("RESIGN_ASSET_RETURN")
                .taskName("返却確認").dueDate(LocalDate.now()).status("WAIVED").build(); tasks.insert(task);
        SysUser approver = SysUser.builder().tenantId(tenant).username("outbox-" + UUID.randomUUID()).password("N/A")
                .realName("検証担当").role("HR").status(1).build(); users.insert(approver);
        ApprovalRequest approval = ApprovalRequest.builder().tenantId(tenant).requestNo("OUT-" + UUID.randomUUID().toString().substring(0, 20))
                .requestType("LIFECYCLE_EXCEPTION").targetType("ENGINEER").targetId(engineer.getId()).targetVersion(0L)
                .applicantId(approver.getId()).payloadJson("{}").routeSnapshotJson("[]").status("APPROVED").version(0).build();
        approvalRequests.insert(approval);
        waivers.insert(AssetOffboardingWaiver.builder().engineerId(engineer.getId()).lifecycleCaseId(resignation.getId())
                .lifecycleTaskId(task.getId()).approvalRequestId(approval.getId()).reason("検証用例外")
                .approvedBy(approver.getId()).approvedAt(LocalDateTime.now()).build());
        var result = clearance.checkOffboardingClearance(engineer.getId(), resignation.getId(), task.getId());
        assertThat(result.isWaived()).isTrue();
        assertThat(result.getUnregisterableAccountCount()).isEqualTo(1);
        assertThat(result.isClearancePassed()).isFalse();
    }

    @Test void 未送信の三状態をpollの選択とclaimと結果更新が除外する() {
        ExternalAccountReference row = account(1L);
        outbox.enqueueForResignation(lifecycle("RESIGNATION", "ACTIVE"), null);
        for (String state : new String[]{REQUESTED, SENDING, FAILED}) {
            references.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExternalAccountReference>()
                    .eq(ExternalAccountReference::getId, row.getId()).set(ExternalAccountReference::getExternalSyncStatus, state));
            var current = references.selectById(row.getId());
            assertThat(references.selectPendingForTenant(tenant, LocalDateTime.now().plusDays(1))).isEmpty();
            assertThat(references.claimRevokePoll(row.getId(), current.getVersion(), LocalDateTime.now().plusDays(1),
                    LocalDateTime.now().plusDays(2), tenant, 1L)).isZero();
            assertThat(references.completeRevokePollWithCas(row.getId(), "UNKNOWN", 1, LocalDateTime.now(),
                    "SYNC_FAILED", "synthetic", tenant, 1L, current.getVersion())).isZero();
        }
    }

    @Test void CAS取得の敗者と古い送信結果は現在の行を変更しない() {
        ExternalAccountReference row = account(1L);
        outbox.enqueueForResignation(lifecycle("RESIGNATION", "ACTIVE"), null);
        ExternalAccountReference queued = references.selectById(row.getId());
        ExternalAccountReference claimed = outbox.claim(queued, LocalDateTime.now().plusMinutes(1));
        assertThat(claimed.getExternalSyncStatus()).isEqualTo(SENDING);
        assertThat(outbox.claim(queued, LocalDateTime.now().plusMinutes(1))).isNull();
        outbox.finish(claimed, true);
        assertThat(outbox.finish(claimed, false)).isNull();
        assertThat(references.selectById(row.getId()).getExternalSyncStatus()).isEqualTo(SENT);
    }

    @Test void 送信済みと結果不明の行は送信候補の件数上限を消費しない() {
        ExternalAccountReference requested = account(1L), sent = account(1L), unknown = account(2L);
        outbox.enqueueForResignation(lifecycle("RESIGNATION", "ACTIVE"), null);
        references.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExternalAccountReference>()
                .eq(ExternalAccountReference::getId, sent.getId()).set(ExternalAccountReference::getExternalSyncStatus, SENT));
        references.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExternalAccountReference>()
                .eq(ExternalAccountReference::getId, unknown.getId()).set(ExternalAccountReference::getExternalSyncStatus, UNKNOWN));
        assertThat(references.selectOutboxForTenant(tenant, LocalDateTime.now().plusDays(1)))
                .extracting(ExternalAccountReference::getId).containsExactly(requested.getId());
    }

    @Test void 最新終結が退社でも後続の再入社案件があれば事後登録しない() {
        LifecycleCase resignation = lifecycle("RESIGNATION", "COMPLETED");
        assertThat(cases.selectLatestCompletedResignationWithoutReentry(tenant, engineer.getId()).getId()).isEqualTo(resignation.getId());
        ExternalAccountReference late = account(1L);
        outbox.enqueueLateRegistration(late, null);
        assertThat(late.getStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(late.getExternalSyncStatus()).isEqualTo(REQUESTED);
        assertThat(references.selectById(late.getId()).getExternalSyncStatus()).isEqualTo(REQUESTED);
        lifecycle("REINSTATEMENT", "DRAFT");
        assertThat(cases.selectLatestCompletedResignationWithoutReentry(tenant, engineer.getId())).isNull();
        ExternalAccountReference reentry = account(1L);
        outbox.enqueueLateRegistration(reentry, null);
        assertThat(references.selectById(reentry.getId()).getIdempotencyKey()).isNull();
    }

    @Test void 実serviceの登録応答は退社後の要求状態と再入社後の通常状態を区別する() {
        lifecycle("RESIGNATION", "COMPLETED");
        ExternalAccountReference late = accountService.createAccountReference(system.getId(), "synthetic-late@example.invalid",
                "ENGINEER", engineer.getId(), "MEMBER", null);
        assertThat(late.getStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(late.getIdempotencyKey()).endsWith(":" + late.getId() + ":g1");
        lifecycle("JOIN", "DRAFT");
        ExternalAccountReference reentry = accountService.registerAccountReference(system.getId(), "synthetic-reentry@example.invalid",
                "ENGINEER", engineer.getId(), "MEMBER", null);
        assertThat(reentry.getStatus()).isEqualTo("ACTIVE");
        assertThat(reentry.getIdempotencyKey()).isNull();
    }

    @Test void 古いgenerationの自動確認で新しい要求を確定できない() {
        ExternalAccountReference row = account(1L);
        outbox.enqueueForResignation(lifecycle("RESIGNATION", "ACTIVE"), null);
        ExternalAccountReference g1 = references.selectById(row.getId());
        ExternalAccountReference sending = outbox.claim(g1, LocalDateTime.now().plusMinutes(1));
        outbox.markSendUnknown(sending);
        outbox.reRequestUnknown(row.getId(), tenant, 1L, null);
        assertThatThrownBy(() -> confirmations.confirm(row.getId(),
                com.ses.common.audit.ActorAttribution.schedulerPoll("stale-result", g1.getIdempotencyKey())))
                .isInstanceOf(com.ses.common.exception.BusinessException.class);
        assertThat(references.selectById(row.getId()).getRevokeConfirmedAt()).isNull();
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "管理者")
    void 未処理一覧はtenantを越えず識別子をマスクする() {
        ExternalAccountReference own = account(1L);
        outbox.enqueueForResignation(lifecycle("RESIGNATION", "ACTIVE"), null);
        ExternalAccountReference foreign = account(2L);
        references.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExternalAccountReference>()
                .eq(ExternalAccountReference::getId, foreign.getId()).set(ExternalAccountReference::getTenantId, "foreign-" + tenant));
        var result = api.pendingRevocations().getData();
        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(own.getId());
            assertThat(dto.getAccountIdentifier()).doesNotContain("private-identifier").contains("***");
        });
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "HR")
    void 再要求APIはHRにも許可しない() {
        assertThatThrownBy(() -> api.reRequestRevoke(1L)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private ExternalAccountReference account(Long legal) {
        ExternalAccountReference row = ExternalAccountReference.builder().tenantId(tenant).legalEntityId(legal).systemId(system.getId())
                .assigneeType("ENGINEER").assigneeId(engineer.getId()).status("ACTIVE").accountIdentifier("private-identifier@example.invalid").build();
        references.insert(row); return row;
    }
    private LifecycleCase lifecycle(String type, String status) {
        LifecycleCase row = LifecycleCase.builder().tenantId(tenant).caseNo("OUT-" + UUID.randomUUID()).lifecycleType(type)
                .engineerId(engineer.getId()).templateId(1L).templateVersion(1).anchorDate(LocalDate.now()).status(status)
                .title("失効要求検証").applicantUserId(1L).engineerSnapshotJson("{}").build();
        cases.insert(row); return row;
    }
}
