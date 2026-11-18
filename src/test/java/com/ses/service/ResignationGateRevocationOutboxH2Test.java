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
@org.springframework.context.annotation.Import(ResignationGateRevocationOutboxH2Test.FractionalClockConfiguration.class)
class ResignationGateRevocationOutboxH2Test {
    @org.springframework.boot.test.context.TestConfiguration
    static class FractionalClockConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        java.time.Clock fractionalClock() {
            return java.time.Clock.fixed(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    .plusNanos(875_000_000), java.time.ZoneId.of("Asia/Tokyo"));
        }
    }
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
    @Autowired java.time.Clock clock;
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

    @Test void 小数秒のClockでも秒精度の登録直後に指定IDだけを送信する() {
        // H2では期限精度とID選択を検証し、実commit境界はM10と単体試験で検証する。
        ExternalAccountReference backlog = account(1L), current = account(2L);
        outbox.request(backlog.getId(), tenant, 1L, "backlog-" + backlog.getId(), null);
        outbox.request(current.getId(), tenant, 2L, "current-" + current.getId(), null);
        var queued = references.selectById(current.getId());
        assertThat(queued.getNextRetryAt().getNano()).isZero();
        assertThat(java.time.LocalDateTime.now(clock).getNano()).isEqualTo(875_000_000);
        var switches = mock(com.ses.config.feature.FeatureSwitches.class);
        when(switches.isEnabled(com.ses.config.feature.Feature.NF01)).thenReturn(true);
        when(provider.requestRevoke(any())).thenReturn(true);
        var directDispatcher = new ExternalAccountRevocationDispatcher(references, outbox, provider, switches, clock);
        assertThat(directDispatcher.dispatchRegistered(java.util.List.of(current.getId()))).isEqualTo(1);
        assertThat(references.selectById(current.getId()).getExternalSyncStatus()).isEqualTo(SENT);
        assertThat(references.selectById(backlog.getId()).getExternalSyncStatus()).isEqualTo(REQUESTED);
        verify(provider).requestRevoke(argThat(row -> row.getId().equals(current.getId())
                && row.getAccountIdentifier().equals("private-identifier@example.invalid")));
        verify(provider, never()).requestRevoke(argThat(row -> row.getId().equals(backlog.getId())));
    }

    @Test void UNKNOWNの確認待ちとtimeoutでも再要求のgenerationを失わない() {
        ExternalAccountReference row = account(1L);
        outbox.enqueueForResignation(lifecycle("RESIGNATION", "ACTIVE"), null);
        outbox.markSendUnknown(outbox.claim(references.selectById(row.getId()), LocalDateTime.now().plusMinutes(1)));
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(accountService);
        for (var outcome : new ExternalAccountProviderClient.RevokeConfirmationStatus[]{
                ExternalAccountProviderClient.RevokeConfirmationStatus.PENDING,
                ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT}) {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "persistConfirmationOutcome",
                    references.selectById(row.getId()), outcome, LocalDateTime.now());
            var stored = references.selectById(row.getId());
            assertThat(stored.getStatus()).isEqualTo("UNKNOWN");
            assertThat(stored.getExternalSyncStatus()).isEqualTo(UNKNOWN);
            assertThat(accountService.pendingRevocations()).extracting(ExternalAccountReference::getId).contains(row.getId());
        }
        outbox.reRequestUnknown(row.getId(), tenant, 1L, null);
        assertThat(references.selectById(row.getId()).getIdempotencyKey()).endsWith(":g2");
    }

    @Test void 取り消した再入社案件は最新終結と再入社の両判定から除外する() {
        LifecycleCase resignation = lifecycle("RESIGNATION", "COMPLETED");
        lifecycle("JOIN", "CANCELLED");
        lifecycle("REINSTATEMENT", "CANCELLED");
        assertThat(cases.selectLatestCompletedResignationWithoutReentry(tenant, engineer.getId()).getId()).isEqualTo(resignation.getId());
        ExternalAccountReference late = account(1L);
        outbox.enqueueLateRegistration(late, null);
        assertThat(references.selectById(late.getId()).getExternalSyncStatus()).isEqualTo(REQUESTED);
    }

    @Test void 例外保留は登録不能として完了を止め原状態を保持する() {
        ExternalAccountReference held = account(1L); held.setStatus("EXCEPTION_HOLD"); references.updateById(held);
        assertThat(clearance.checkOffboardingClearanceForTenant(tenant, engineer.getId(), null, null).getUnregisterableAccountCount()).isEqualTo(1);
        assertThatThrownBy(() -> outbox.enqueueForResignation(lifecycle("RESIGNATION", "ACTIVE"), null))
                .isInstanceOf(com.ses.common.exception.BusinessException.class);
        assertThat(references.selectById(held.getId()).getStatus()).isEqualTo("EXCEPTION_HOLD");
    }

    @Test void NF09のDTOは原値を維持しNF01の追加DTOだけマスクする() {
        ExternalAccountReference row = account(1L);
        assertThat(com.ses.dto.asset.ExternalAccountReferenceDto.from(row).getAccountIdentifier()).isEqualTo(row.getAccountIdentifier());
        assertThat(com.ses.dto.asset.ExternalAccountReferenceDto.forPendingRevocation(row).getAccountIdentifier()).isEqualTo("pr***@example.invalid");
        assertThatThrownBy(() -> clearance.checkOffboardingClearanceForTenant("foreign-" + tenant, engineer.getId(), null, null))
                .isInstanceOf(com.ses.common.exception.BusinessException.class);
    }

    @Test void 登録済みと登録不能と未登録を重複しない件数として返す() {
        ExternalAccountReference registered = account(1L); registered.setIdempotencyKey("registered-request"); references.updateById(registered);
        ExternalAccountReference held = account(1L); held.setStatus("EXCEPTION_HOLD"); held.setIdempotencyKey("held-request"); references.updateById(held);
        account(2L);
        ExternalAccountReference blank = account(2L); blank.setIdempotencyKey(" "); references.updateById(blank);
        var result = clearance.checkOffboardingClearanceForTenant(tenant, engineer.getId(), null, null);
        assertThat(result.getUnrevokedAccountCount()).isEqualTo(4);
        assertThat(result.getRegisteredAccountCount()).isEqualTo(1);
        assertThat(result.getUnregisterableAccountCount()).isEqualTo(2);
        assertThat(result.getUnrevokedAccountCount() - result.getRegisteredAccountCount() - result.getUnregisterableAccountCount()).isEqualTo(1);
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
