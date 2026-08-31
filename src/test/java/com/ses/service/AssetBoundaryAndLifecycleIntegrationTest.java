package com.ses.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.BaseIntegrationTest;
import com.ses.common.exception.BusinessException;
import com.ses.dto.asset.OffboardingClearanceResultDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.provider.impl.MockExternalAccountProviderClientImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Asset Comprehensive Boundary & Integration Tests (境界・並行性・Recovery・スコープ検証)")
class AssetBoundaryAndLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetAssignmentService assetAssignmentService;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private AssetEventMapper assetEventMapper;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private LicensePlanMapper licensePlanMapper;

    @Autowired
    private LicenseAssignmentMapper licenseAssignmentMapper;

    @Autowired
    private AssetInventoryService assetInventoryService;

    @Autowired
    private AssetInventoryRunMapper assetInventoryRunMapper;

    @Autowired
    private AssetInventoryItemMapper assetInventoryItemMapper;

    @Autowired
    private ExternalAccountService externalAccountService;

    @Autowired
    private ExternalAccountSystemMapper externalAccountSystemMapper;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private AssetOffboardingService assetOffboardingService;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private LifecycleCaseMapper lifecycleCaseMapper;

    @Autowired
    private LifecycleTaskMapper lifecycleTaskMapper;

    @Autowired
    private LifecycleTemplateMapper lifecycleTemplateMapper;

    @Autowired
    private AssetScopeService assetScopeService;

    @Autowired
    private ExternalAccountProviderClient providerClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Boundary 1: Re-assign immediately after return succeeds")
    void testReassignImmediatelyAfterReturn() {
        Asset asset = Asset.builder()
                .assetTag("AST-REASSIGN-001")
                .assetName("ThinkPad L14")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        AssetAssignment as1 = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 101L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), null, "Initial", 1L);
        assertThat(as1.getStatus()).isEqualTo("ACTIVE");

        AssetAssignment returnedAs = assetAssignmentService.returnAssignment(as1.getId(), LocalDate.of(2026, 8, 15), null, "Returned OK", 1L);
        assertThat(returnedAs.getStatus()).isEqualTo("RETURNED");
        assertThatThrownBy(() -> assetAssignmentService.softDeleteAssignment(returnedAs.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("貸与履歴は論理削除できません");

        Asset updatedAsset = assetService.getById(asset.getId());
        assertThat(updatedAsset.getStatus()).isEqualTo("IN_STOCK");

        AssetAssignment as2 = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 102L, LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 31), null, "Second", 1L);
        assertThat(as2.getStatus()).isEqualTo("ACTIVE");
        assertThat(as2.getAssigneeId()).isEqualTo(102L);
        assertThat(assetService.getById(asset.getId()).getStatus()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("Boundary 1-C: actual return date must be within start date and today")
    void testActualReturnDateRangeIsEnforced() {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(3);
        Asset asset = Asset.builder()
                .assetTag("AST-RETURN-RANGE-" + System.nanoTime())
                .assetName("Return range device")
                .category("PC")
                .build();
        assetService.createAsset(asset, 1L);
        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 103L, startDate, today.plusDays(7), null, "Return range", 1L);

        assertThatThrownBy(() -> assetAssignmentService.returnAssignment(
                assignment.getId(), startDate.minusDays(1), null, "開始日前返却", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("開始日以降かつ本日以前");
        assertThatThrownBy(() -> assetAssignmentService.returnAssignment(
                assignment.getId(), today.plusDays(1), null, "未来日返却", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("開始日以降かつ本日以前");
        assertThat(assetAssignmentService.returnAssignment(
                assignment.getId(), today, null, "本日返却", 1L).getStatus()).isEqualTo("RETURNED");
    }

    @Test
    @DisplayName("Boundary 1-B: NF-01 asset/license blockers use the status OR date contract")
    void testOffboardingBlockersUseStatusOrDateContract() {
        Long engineerId = 10101L;

        Asset activeWithReturnDate = Asset.builder()
                .assetTag("AST-BLOCKER-OR-A-" + System.nanoTime())
                .assetName("Inconsistent active asset")
                .category("PC")
                .status("IN_STOCK")
                .build();
        Asset returnedWithoutDate = Asset.builder()
                .assetTag("AST-BLOCKER-OR-B-" + System.nanoTime())
                .assetName("Inconsistent returned asset")
                .category("MONITOR")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(activeWithReturnDate, 1L);
        assetService.createAsset(returnedWithoutDate, 1L);
        assetAssignmentMapper.insert(AssetAssignment.builder()
                .assetId(activeWithReturnDate.getId())
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .startDate(LocalDate.of(2026, 8, 1))
                .actualReturnDate(LocalDate.of(2026, 8, 15))
                .status("ACTIVE")
                .build());
        assetAssignmentMapper.insert(AssetAssignment.builder()
                .assetId(returnedWithoutDate.getId())
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .startDate(LocalDate.of(2026, 8, 1))
                .status("RETURNED")
                .build());

        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-BLOCKER-OR-" + System.nanoTime())
                .planName("Inconsistent blocker license")
                .seatLimit(10)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);
        licenseAssignmentMapper.insert(LicenseAssignment.builder()
                .planId(plan.getId())
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .assignedDate(LocalDate.of(2026, 8, 1))
                .releasedDate(LocalDate.of(2026, 8, 15))
                .status("ACTIVE")
                .build());
        licenseAssignmentMapper.insert(LicenseAssignment.builder()
                .planId(plan.getId())
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .assignedDate(LocalDate.of(2026, 8, 1))
                .status("RELEASED")
                .build());

        OffboardingClearanceResultDto result = assetOffboardingService
                .checkOffboardingClearance(engineerId, null, null);

        assertThat(result.getUnreturnedAssetCount()).isEqualTo(2);
        assertThat(result.getUnreleasedLicenseCount()).isEqualTo(2);
        assertThat(result.isClearancePassed()).isFalse();
    }

    @Test
    @DisplayName("Boundary 2: License seat limit boundary (-1, =, +1), concurrent allocation, release & re-assign")
    void testLicenseSeatLimitBoundaryMinusOneEqualPlusOneAndReassign() {
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-BOUND-001")
                .planName("JetBrains All Products")
                .seatLimit(2)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);

        // 1席目 (seat_limit - 1)
        LicenseAssignment lic1 = licenseService.assignLicense(plan.getId(), "ENGINEER", 201L, null, LocalDate.now(), 1L);
        assertThat(lic1.getStatus()).isEqualTo("ACTIVE");
        assertThat(licensePlanMapper.selectById(plan.getId()).getAllocatedCount()).isEqualTo(1);

        // 2席目 (seat_limit = 2 到達)
        LicenseAssignment lic2 = licenseService.assignLicense(plan.getId(), "ENGINEER", 202L, null, LocalDate.now(), 1L);
        assertThat(lic2.getStatus()).isEqualTo("ACTIVE");
        assertThat(licensePlanMapper.selectById(plan.getId()).getAllocatedCount()).isEqualTo(2);

        // 3席目 (seat_limit + 1 超過) -> 拒否
        assertThatThrownBy(() -> licenseService.assignLicense(plan.getId(), "ENGINEER", 203L, null, LocalDate.now(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");

        // 解放と再割当
        licenseService.releaseLicense(lic1.getId(), LocalDate.now(), 1L);
        assertThat(licensePlanMapper.selectById(plan.getId()).getAllocatedCount()).isEqualTo(1);

        LicenseAssignment lic3 = licenseService.assignLicense(plan.getId(), "ENGINEER", 204L, null, LocalDate.now(), 1L);
        assertThat(lic3.getStatus()).isEqualTo("ACTIVE");
        assertThat(licensePlanMapper.selectById(plan.getId()).getAllocatedCount()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    @DisplayName("Boundary 2-B: License concurrent allocation with CAS protection (4 threads for 2 seats)")
    void testLicenseConcurrentAllocationWithCas() throws Exception {
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-CONCUR-001")
                .planName("Figma Enterprise Concurrency Plan")
                .seatLimit(2)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final long engineerId = 5000L + i;
            executor.submit(() -> {
                try {
                    latch.await();
                    licenseService.assignLicense(plan.getId(), "ENGINEER", engineerId, null, LocalDate.now(), 1L);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // 2席上限なので成功は2件、失敗は2件
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(failCount.get()).isEqualTo(2);

        LicensePlan finalPlan = licensePlanMapper.selectById(plan.getId());
        assertThat(finalPlan.getAllocatedCount()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    @DisplayName("Boundary 2-C: License concurrent release decrements seats once")
    void testLicenseConcurrentReleaseDecrementsOnce() throws Exception {
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-RELEASE-CONCUR-" + System.nanoTime())
                .planName("Concurrent Release Plan")
                .seatLimit(1)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);
        LicenseAssignment assignment = licenseService.assignLicense(
                plan.getId(), "ENGINEER", 5101L, null, LocalDate.now(), 1L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    licenseService.releaseLicense(assignment.getId(), LocalDate.now(), 1L);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(failures).hasValue(0);
        assertThat(licensePlanMapper.selectById(plan.getId()).getAllocatedCount()).isZero();
        assertThat(licenseAssignmentMapper.selectById(assignment.getId()).getStatus()).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("Boundary 3: Inventory disallow update after complete & disallow double complete")
    void testInventoryDisallowUpdateAndDoubleComplete() {
        AssetInventoryRun run = assetInventoryService.startInventoryRun("INV-2026-Q3", "2026-Q3棚卸し", LocalDate.now(), 1L);
        assertThat(run.getStatus()).isEqualTo("IN_PROGRESS");

        List<AssetInventoryItem> items = assetInventoryItemMapper.selectList(
                new LambdaQueryWrapper<AssetInventoryItem>().eq(AssetInventoryItem::getInventoryRunId, run.getId()));
        if (!items.isEmpty()) {
            AssetInventoryItem firstItem = items.get(0);
            assetInventoryService.recordItemCheck(firstItem.getId(), "IN_STOCK", "本社5F", "MATCH", "正常確認", "なし", 1L);
        }

        AssetInventoryRun completedRun = assetInventoryService.completeInventoryRun(run.getId(), 1L);
        assertThat(completedRun.getStatus()).isEqualTo("COMPLETED");

        assertThatThrownBy(() -> assetInventoryService.completeInventoryRun(run.getId(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("既に完了");

        if (!items.isEmpty()) {
            AssetInventoryItem firstItem = items.get(0);
            assertThatThrownBy(() -> assetInventoryService.recordItemCheck(firstItem.getId(), "LOST", "不明", "MISSING", "事後変更", "紛失起票", 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("完了済み");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    @DisplayName("Boundary 3-B: Inventory completion is single-winner under concurrency")
    void testInventoryConcurrentCompletionSingleWinner() throws Exception {
        AssetInventoryRun run = assetInventoryService.startInventoryRun(
                "INV-CONCURRENT-" + System.nanoTime(), "並行確定棚卸し", LocalDate.now(), 1L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    assetInventoryService.completeInventoryRun(run.getId(), 1L);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(success).hasValue(1);
        assertThat(failure).hasValue(1);
        assertThat(assetInventoryRunMapper.selectById(run.getId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    @DisplayName("Boundary 3-C: Return and waiver have one terminal winner and one event")
    void testConcurrentReturnAndWaiveSingleTerminalEvent() throws Exception {
        Asset asset = Asset.builder()
                .assetTag("AST-RETURN-WAIVE-CONCUR-" + System.nanoTime())
                .assetName("Return Waive Concurrency PC")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);
        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 5201L, LocalDate.now(), LocalDate.now().plusDays(30), null, "並行検証", 1L);
        ApprovalRequest waiverApproval = ApprovalRequest.builder()
                .requestNo("AR-RETURN-WAIVE-" + System.nanoTime())
                .requestType("LIFECYCLE_EXCEPTION")
                .targetType("ASSET_ASSIGNMENT")
                .targetId(assignment.getId())
                .applicantId(1L)
                .payloadJson("{}")
                .routeSnapshotJson("[]")
                .status("APPROVED")
                .version(1)
                .build();
        approvalRequestMapper.insert(waiverApproval);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        executor.submit(() -> {
            try {
                start.await();
                assetAssignmentService.returnAssignment(assignment.getId(), LocalDate.now(), null, "返却", 1L);
                success.incrementAndGet();
            } catch (Exception e) {
                failure.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        executor.submit(() -> {
            try {
                start.await();
                assetAssignmentService.waiveAssignment(assignment.getId(), "承認済み例外", waiverApproval.getId(), 1L);
                success.incrementAndGet();
            } catch (Exception e) {
                failure.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(success).hasValue(1);
        assertThat(failure).hasValue(1);
        AssetAssignment finalAssignment = assetAssignmentMapper.selectById(assignment.getId());
        assertThat(finalAssignment.getStatus()).isIn("RETURNED", "WAIVED");
        Long terminalEvents = assetEventMapper.selectCount(new LambdaQueryWrapper<AssetEvent>()
                .eq(AssetEvent::getAssetId, asset.getId())
                .in(AssetEvent::getEventType, List.of("RETURNED", "WAIVED")));
        assertThat(terminalEvents).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    @DisplayName("Boundary 4: Provider recovery, idempotency key, next_retry_at & polling job")
    void testProviderRecoveryAndIdempotency() {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("SLACK_RECOVERY_POLL")
                .systemName("Slack Recovery Test")
                .systemType("SAAS_CHAT")
                .isActive(1)
                .build();
        externalAccountSystemMapper.insert(system);

        ExternalAccountReference ref = ExternalAccountReference.builder()
                .systemId(system.getId())
                .accountIdentifier("recovery.user@ses-test.jp")
                .assigneeType("ENGINEER")
                .assigneeId(301L)
                .status("ACTIVE")
                .build();
        externalAccountReferenceMapper.insert(ref);

        if (providerClient instanceof MockExternalAccountProviderClientImpl mockClient) {
            mockClient.setMockStatus(ref.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT);

            // 冪等キーを付与して失効要求送信 -> PENDING_CONFIRMATION へ
            String idempotencyKey = "REVOKE-IDEM-" + ref.getId() + "-20260828";
            ExternalAccountReference requested = externalAccountService.requestRevokeWithIdempotency(ref.getId(), idempotencyKey, 1L);

            assertThat(requested.getStatus()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(requested.getIdempotencyKey()).isEqualTo(idempotencyKey);
            assertThat(requested.getNextRetryAt()).isNotNull();
            assertThat(requested.getRevokeConfirmedAt()).isNull();

            int requestCountAfterFirstSend = mockClient.getRequestCount();
            ExternalAccountReference duplicateRequest = externalAccountService.requestRevokeWithIdempotency(
                    ref.getId(), idempotencyKey, 1L);
            assertThat(duplicateRequest.getIdempotencyKey()).isEqualTo(idempotencyKey);
            assertThat(mockClient.getRequestCount()).isEqualTo(requestCountAfterFirstSend);
            assertThatThrownBy(() -> externalAccountService.requestRevokeWithIdempotency(
                    ref.getId(), "REVOKE-DIFFERENT-" + ref.getId(), 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("別の失効要求");

            // 応答形式を分類できない場合だけ UNKNOWN とし、退社 blocker を維持する
            ExternalAccountReference unknownRef = ExternalAccountReference.builder()
                    .systemId(system.getId())
                    .accountIdentifier("unknown.user@ses-test.jp")
                    .assigneeType("ENGINEER")
                    .assigneeId(302L)
                    .status("ACTIVE")
                    .build();
            externalAccountReferenceMapper.insert(unknownRef);
            mockClient.setMockStatus(unknownRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.UNKNOWN);
            ExternalAccountReference unknown = externalAccountService.requestRevokeWithIdempotency(
                    unknownRef.getId(), "REVOKE-UNKNOWN-" + unknownRef.getId(), 1L);
            assertThat(unknown.getStatus()).isEqualTo("UNKNOWN");
            assertThat(unknown.getRevokeConfirmedAt()).isNull();

            // プロバイダ復旧
            mockClient.setMockStatus(ref.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);
            mockClient.setMockStatus(unknownRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);

            // ポーリングジョブ実行 -> 自動で REVOKED へ (SYSTEM主体として記録され、ユーザーID 1に偽装されないこと)
            int processed = externalAccountService.processPendingRevokePollJob();
            assertThat(processed).isGreaterThanOrEqualTo(2);

            ExternalAccountReference confirmedRef = externalAccountReferenceMapper.selectById(ref.getId());
            assertThat(confirmedRef.getStatus()).isEqualTo("REVOKED");
            assertThat(confirmedRef.getRevokeConfirmedAt()).isNotNull();
            assertThat(confirmedRef.getRevokeConfirmedBy()).isNull();
            assertThat(confirmedRef.getRevokeConfirmedSource()).isEqualTo("SCHEDULER_POLL");

            ExternalAccountReference confirmedUnknownRef = externalAccountReferenceMapper.selectById(unknownRef.getId());
            assertThat(confirmedUnknownRef.getStatus()).isEqualTo("REVOKED");
            assertThat(confirmedUnknownRef.getRevokeConfirmedBy()).isNull();
            assertThat(confirmedUnknownRef.getRevokeConfirmedSource()).isEqualTo("SCHEDULER_POLL");

            // 二重失効確認は安全に冪等処理されること (既にREVOKED済みの行は上書きされない)
            ExternalAccountReference duplicateRevoked = externalAccountService.confirmRevokeFromSchedulerPoll(
                    ref.getId(), "scheduler-poll:duplicate-" + ref.getId(), ref.getIdempotencyKey());
            assertThat(duplicateRevoked.getStatus()).isEqualTo("REVOKED");
            assertThat(duplicateRevoked.getRevokeConfirmedBy()).isNull();
            assertThat(duplicateRevoked.getRevokeConfirmedSource()).isEqualTo("SCHEDULER_POLL");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("NF-09 System vs Manual actor attribution: Provider poll logs SYSTEM without spoofing user 1")
    void testSystemVsManualActorAttribution() {
        if (providerClient instanceof MockExternalAccountProviderClientImpl) {
            MockExternalAccountProviderClientImpl mockClient = (MockExternalAccountProviderClientImpl) providerClient;

            ExternalAccountSystem system = ExternalAccountSystem.builder()
                    .systemCode("SYS_MANUAL_ATTR_" + System.nanoTime())
                    .systemName("System vs Manual Attribution System")
                    .systemType("IDP")
                    .build();
            externalAccountService.saveSystem(system);

            // 1. 手動失効確認: 実ユーザーIDが記録され、source = MANUAL
            ExternalAccountReference manualRef = externalAccountService.createAccountReference(
                    system.getId(), "manual-actor@ses-test.jp", "ENGINEER", 7701L, "DEVELOPER", 9901L);
            assertThat(manualRef.getStatus()).isEqualTo("ACTIVE");

            // H2のseed済みadmin(sys_user.id=1)を実在する確認主体として使う。
            Long humanUserId = 1L;
            ExternalAccountReference manualRevoked = externalAccountService.confirmRevoke(manualRef.getId(), humanUserId);
            assertThat(manualRevoked.getStatus()).isEqualTo("REVOKED");
            assertThat(manualRevoked.getRevokeConfirmedAt()).isNotNull();
            assertThat(manualRevoked.getRevokeConfirmedBy()).isEqualTo(humanUserId);
            assertThat(manualRevoked.getRevokeConfirmedSource()).isEqualTo("MANUAL_API");

            // 2. 自動ポーリング失効確認: confirmedBy は NULL（主キー1の偽装禁止）、source = SYSTEM
            ExternalAccountReference autoRef = externalAccountService.createAccountReference(
                    system.getId(), "auto-poll-actor@ses-test.jp", "ENGINEER", 7702L, "MEMBER", 9901L);
            mockClient.setMockStatus(autoRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT);
            externalAccountService.requestRevokeWithIdempotency(autoRef.getId(), "auto-key-" + autoRef.getId(), 9901L);

            mockClient.setMockStatus(autoRef.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);

            int polled = externalAccountService.processPendingRevokePollJob();
            assertThat(polled).isGreaterThanOrEqualTo(1);

            ExternalAccountReference autoRevoked = externalAccountReferenceMapper.selectById(autoRef.getId());
            assertThat(autoRevoked.getStatus()).isEqualTo("REVOKED");
            assertThat(autoRevoked.getRevokeConfirmedAt()).isNotNull();
            assertThat(autoRevoked.getRevokeConfirmedBy()).isNull();
            assertThat(autoRevoked.getRevokeConfirmedSource()).isEqualTo("SCHEDULER_POLL");
        }
    }

    @Test
    @DisplayName("Boundary 5: Offboarding blocker 3 categories & waiver bypass")
    void testOffboardingThreeBlockers() {
        Long engineerId = 9999L;

        Asset asset = Asset.builder()
                .assetTag("AST-BLK-001")
                .assetName("Blocker Device")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);
        assetAssignmentService.createAssignment(asset.getId(), "ENGINEER", engineerId, LocalDate.now(), LocalDate.now().plusMonths(1), null, "貸与", 1L);

        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("SYS-BLK-001")
                .systemName("Blocker SaaS")
                .systemType("SAAS_SCM")
                .isActive(1)
                .build();
        externalAccountSystemMapper.insert(system);
        ExternalAccountReference ref = ExternalAccountReference.builder()
                .systemId(system.getId())
                .accountIdentifier("blk@ses-test.jp")
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .status("ACTIVE")
                .build();
        externalAccountReferenceMapper.insert(ref);

        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-BLK-001")
                .planName("Blocker License")
                .seatLimit(10)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);
        licenseService.assignLicense(plan.getId(), "ENGINEER", engineerId, ref.getId(), LocalDate.now(), 1L);

        LifecycleTemplate lifecycleTemplate = LifecycleTemplate.builder()
                .templateType("RESIGNATION")
                .name("退社資産scope test")
                .versionNo(1)
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusDays(1))
                .build();
        lifecycleTemplateMapper.insert(lifecycleTemplate);
        LifecycleCase lifecycleCase = LifecycleCase.builder()
                .caseNo("LC-BLOCKER-" + System.nanoTime())
                .lifecycleType("RESIGNATION")
                .engineerId(engineerId)
                .templateId(lifecycleTemplate.getId())
                .templateVersion(1)
                .anchorDate(LocalDate.now())
                .status("ACTIVE")
                .title("退社blocker scope test")
                .applicantUserId(1L)
                .engineerSnapshotJson("{}")
                .build();
        lifecycleCaseMapper.insert(lifecycleCase);
        LifecycleTask lifecycleTask = LifecycleTask.builder()
                .caseId(lifecycleCase.getId())
                .taskCode("RESIGN_ASSET_RETURN")
                .taskName("貸与資産返却")
                .dueDate(LocalDate.now())
                .status("PENDING")
                .build();
        lifecycleTaskMapper.insert(lifecycleTask);
        OffboardingClearanceResultDto result = assetOffboardingService.checkOffboardingClearance(
                engineerId, lifecycleCase.getId(), lifecycleTask.getId());
        assertThat(result.isClearancePassed()).isFalse();
        assertThat(result.getUnreturnedAssetCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getUnrevokedAccountCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getUnreleasedLicenseCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getBlockingItems()).hasSize(3);

        ApprovalRequest approval = ApprovalRequest.builder()
                .requestNo("AR-BLK-9999")
                .requestType("LIFECYCLE_EXCEPTION")
                .targetType("ENGINEER")
                .targetId(engineerId)
                .applicantId(1L)
                .payloadJson("{}")
                .routeSnapshotJson("[]")
                .status("APPROVED")
                .version(1)
                .build();
        approvalRequestMapper.insert(approval);
        assetOffboardingService.approveOffboardingWaiver(
                engineerId, lifecycleCase.getId(), lifecycleTask.getId(),
                "役員特例承認済み", approval.getId(), 1L);
        OffboardingClearanceResultDto waivedResult = assetOffboardingService.checkOffboardingClearance(
                engineerId, lifecycleCase.getId(), lifecycleTask.getId());
        assertThat(waivedResult.isClearancePassed()).isTrue();
        assertThat(waivedResult.isWaived()).isTrue();
    }

    @Autowired
    private com.ses.mapper.DocumentLinkMapper documentLinkMapper;

    @Autowired
    private com.ses.mapper.DocumentMapper documentMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerAccountLinkService engineerAccountLinkService;

    @Autowired
    private com.ses.service.impl.AssetScopeServiceImpl assetScopeServiceImpl;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerSalesMapper engineerSalesMapper;

    @Test
    @DisplayName("Boundary 6: 営業・要員 Fail-Closed Scope 検証（担当外要員・別法人は拒否）")
    void testOrganizationScopeAndMultiCorporationIsolation() {
        // 1. 法人A資産と法人B資産を作成
        Asset assetA = Asset.builder()
                .assetTag("AST-SCOPE-A-001")
                .assetName("Corp A MacBook Pro")
                .category("PC")
                .ownerCompanyId(100L)
                .status("IN_STOCK")
                .build();
        assetService.createAsset(assetA, 1L);

        Asset assetB = Asset.builder()
                .assetTag("AST-SCOPE-B-001")
                .assetName("Corp B ThinkPad")
                .category("PC")
                .ownerCompanyId(200L)
                .status("IN_STOCK")
                .build();
        assetService.createAsset(assetB, 1L);

        // 2. 管理者/HR: 両法人の全資産を閲覧可能
        assertThat(assetScopeService.isAccessible(assetA.getId(), "管理者", 1L)).isTrue();
        assertThat(assetScopeService.isAccessible(assetB.getId(), "管理者", 1L)).isTrue();
        assertThat(assetScopeService.isAccessible(assetA.getId(), "HR", 1L)).isTrue();
        assertThat(assetScopeService.isAccessible(assetB.getId(), "HR", 1L)).isTrue();

        // 3. 要員Aに資産Aを貸与し、要員Aユーザーと要員Bユーザーを登録
        AssetAssignment asA = assetAssignmentService.createAssignment(
                assetA.getId(), "ENGINEER", 8801L,
                LocalDate.now(), LocalDate.now().plusMonths(1), null, "貸与A", 1L);

        SysUser userEngA = SysUser.builder()
                .username("eng-scope-8801")
                .password("pass")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(userEngA);
        engineerAccountLinkService.link(8801L, userEngA.getId(), 1L);

        SysUser userEngB = SysUser.builder()
                .username("eng-scope-8802")
                .password("pass")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(userEngB);
        engineerAccountLinkService.link(8802L, userEngB.getId(), 1L);

        // 4. 要員スコープ: 自己 ACTIVE 貸与資産のみ可視、他要員への貸与・未貸与・別法人資産は不可視
        assertThat(assetScopeService.isAccessible(assetA.getId(), "要員", userEngA.getId()))
                .as("要員Aは自己貸与資産Aに可視").isTrue();
        assertThat(assetScopeService.isAccessible(assetB.getId(), "要員", userEngA.getId()))
                .as("要員Aは法人B資産Bに不可視").isFalse();
        assertThat(assetScopeService.isAccessible(assetA.getId(), "要員", userEngB.getId()))
                .as("要員Bは要員Aへの貸与資産Aに不可視（Fail-Closed）").isFalse();

        // 5. 返却後は要員Aも資産Aへのアクセスが不可（ACTIVE貸与なし）
        assetAssignmentService.returnAssignment(asA.getId(), LocalDate.now(), null, "返却", 1L);
        assertThat(assetScopeService.isAccessible(assetA.getId(), "要員", userEngA.getId()))
                .as("返却後は要員Aも資産Aへ不可視").isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    @DisplayName("Boundary 6-B: 営業/マネージャーの貸与・法人scopeをfail-closedで分離")
    void testSalesAndManagerScopeUsesAssignmentAndManagedOrganization() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        long legalA = 91001L;
        long legalB = 91002L;

        OrganizationUnit orgA = OrganizationUnit.builder()
                .legalEntityId(legalA).code("ASSET-SCOPE-A-" + suffix).name("資産Scope法人A")
                .type("DEPARTMENT").validFrom(LocalDate.of(2026, 1, 1)).status("有効").build();
        OrganizationUnit orgB = OrganizationUnit.builder()
                .legalEntityId(legalB).code("ASSET-SCOPE-B-" + suffix).name("資産Scope法人B")
                .type("DEPARTMENT").validFrom(LocalDate.of(2026, 1, 1)).status("有効").build();
        organizationUnitMapper.insert(orgA);
        organizationUnitMapper.insert(orgB);

        Engineer engineerA = Engineer.builder().fullName("資産Scope要員A-" + suffix)
                .employmentType("正社員").status("稼動中").organizationId(orgA.getId()).build();
        Engineer engineerB = Engineer.builder().fullName("資産Scope要員B-" + suffix)
                .employmentType("正社員").status("稼動中").organizationId(orgB.getId()).build();
        engineerMapper.insert(engineerA);
        engineerMapper.insert(engineerB);

        SysUser sales = SysUser.builder().username("asset-sales-" + suffix).password("pass").role("営業").status(1).build();
        SysUser manager = SysUser.builder().username("asset-manager-" + suffix).password("pass").role("マネージャー").status(1).build();
        sysUserMapper.insert(sales);
        sysUserMapper.insert(manager);
        userOrganizationMapper.insert(UserOrganization.builder().userId(sales.getId()).organizationId(orgA.getId())
                .primaryFlag(1).validFrom(LocalDate.of(2026, 1, 1)).build());
        userOrganizationMapper.insert(UserOrganization.builder().userId(manager.getId()).organizationId(orgA.getId())
                .primaryFlag(1).validFrom(LocalDate.of(2026, 1, 1)).build());
        engineerSalesMapper.insert(EngineerSales.builder().engineerId(engineerA.getId()).salesUserId(sales.getId())
                .primaryFlag(1).assignedAt(LocalDate.of(2026, 1, 1)).build());

        Asset assignedA = Asset.builder().assetTag("AST-SCOPE-SALES-A-" + suffix).assetName("法人A貸与")
                .category("PC").ownerCompanyId(legalA).status("IN_STOCK").build();
        Asset assignedB = Asset.builder().assetTag("AST-SCOPE-SALES-B-" + suffix).assetName("法人B貸与")
                .category("PC").ownerCompanyId(legalB).status("IN_STOCK").build();
        Asset unassignedA = Asset.builder().assetTag("AST-SCOPE-SALES-U-A-" + suffix).assetName("法人A未貸与")
                .category("MONITOR").ownerCompanyId(legalA).status("IN_STOCK").build();
        Asset unassignedB = Asset.builder().assetTag("AST-SCOPE-SALES-U-B-" + suffix).assetName("法人B未貸与")
                .category("MONITOR").ownerCompanyId(legalB).status("IN_STOCK").build();
        Asset crossCorporation = Asset.builder().assetTag("AST-SCOPE-SALES-CROSS-" + suffix).assetName("担当要員の別法人資産")
                .category("TABLET").ownerCompanyId(legalB).status("IN_STOCK").build();
        Asset sharedAssigned = Asset.builder().assetTag("AST-SCOPE-SALES-SHARED-" + suffix).assetName("共有資産の貸与")
                .category("SECURITY_KEY").ownerCompanyId(null).status("IN_STOCK").build();
        assetService.createAsset(assignedA, 1L);
        assetService.createAsset(assignedB, 1L);
        assetService.createAsset(unassignedA, 1L);
        assetService.createAsset(unassignedB, 1L);
        assetService.createAsset(crossCorporation, 1L);
        assetService.createAsset(sharedAssigned, 1L);
        assetAssignmentService.createAssignment(assignedA.getId(), "ENGINEER", engineerA.getId(),
                LocalDate.now(), LocalDate.now().plusMonths(1), null, "A", 1L);
        Document crossScopeEvidence = new Document();
        crossScopeEvidence.setTenantId("default");
        crossScopeEvidence.setDocumentType("INTERNAL");
        crossScopeEvidence.setTitle("別法人資産の証跡");
        crossScopeEvidence.setDirection("INTERNAL");
        crossScopeEvidence.setStatus("DRAFT");
        crossScopeEvidence.setCurrency("JPY");
        crossScopeEvidence.setLegalHoldFlag(0);
        crossScopeEvidence.setVersion(1L);
        documentMapper.insert(crossScopeEvidence);
        assetAssignmentService.createAssignment(assignedB.getId(), "ENGINEER", engineerB.getId(),
                LocalDate.now(), LocalDate.now().plusMonths(1), crossScopeEvidence.getId(), "B", 1L);
        assetAssignmentService.createAssignment(crossCorporation.getId(), "ENGINEER", engineerA.getId(),
                LocalDate.now(), LocalDate.now().plusMonths(1), null, "A担当・法人B保有", 1L);
        assetAssignmentService.createAssignment(sharedAssigned.getId(), "ENGINEER", engineerA.getId(),
                LocalDate.now(), LocalDate.now().plusMonths(1), null, "共有", 1L);

        assertThat(assetScopeService.isAccessible(assignedA.getId(), "営業", sales.getId())).isTrue();
        assertThat(assetScopeService.isAccessible(assignedB.getId(), "営業", sales.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(unassignedA.getId(), "営業", sales.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(unassignedB.getId(), "営業", sales.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(crossCorporation.getId(), "営業", sales.getId())).isTrue();
        assertThat(assetScopeService.isAccessible(sharedAssigned.getId(), "営業", sales.getId())).isTrue();
        assertThat(assetScopeService.isAccessible(assignedA.getId(), "マネージャー", manager.getId())).isTrue();
        assertThat(assetScopeService.isAccessible(assignedB.getId(), "マネージャー", manager.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(unassignedA.getId(), "マネージャー", manager.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(unassignedB.getId(), "マネージャー", manager.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(crossCorporation.getId(), "マネージャー", manager.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(sharedAssigned.getId(), "マネージャー", manager.getId())).isTrue();

        // 管理者/HR以外は、別法人資産にしか紐づかない文書IDを紛失台帳へ横取りできない。
        assertThatThrownBy(() -> assetService.reportLost(
                assignedA.getId(), "別法人文書を不正指定", manager.getId(), crossScopeEvidence.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("スコープ外");
        assertThat(assetService.getById(assignedA.getId()).getStatus()).isEqualTo("ASSIGNED");

        // 紛失インシデントだけにリンクされた文書も、担当範囲内のマネージャーは参照できる。
        Asset managerLostAsset = Asset.builder().assetTag("AST-SCOPE-LOST-" + suffix)
                .assetName("マネージャー担当紛失資産").category("PC")
                .ownerCompanyId(legalA).status("IN_STOCK").build();
        assetService.createAsset(managerLostAsset, 1L);
        assetAssignmentService.createAssignment(managerLostAsset.getId(), "ENGINEER", engineerA.getId(),
                LocalDate.now(), LocalDate.now().plusMonths(1), null, "紛失", 1L);
        Document lostEvidence = new Document();
        lostEvidence.setTenantId("default");
        lostEvidence.setDocumentType("INTERNAL");
        lostEvidence.setTitle("紛失インシデント証跡");
        lostEvidence.setDirection("INTERNAL");
        lostEvidence.setStatus("DRAFT");
        lostEvidence.setCurrency("JPY");
        lostEvidence.setLegalHoldFlag(0);
        lostEvidence.setVersion(1L);
        documentMapper.insert(lostEvidence);
        assetService.reportLost(managerLostAsset.getId(), "担当範囲内の紛失", 1L, lostEvidence.getId());
        mockMvc.perform(get("/api/documents/" + lostEvidence.getId())
                        .with(SecurityMockMvcRequestPostProcessors.user(String.valueOf(manager.getId()))
                                .roles("マネージャー")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Boundary 7: DocumentLink 登録と isAccessibleByDocumentLink でのスコープ導出・別要員拒否")
    void testDocumentEvidenceScopeRejection() throws Exception {
        long engineerAId = 18801L;
        long engineerBId = 18802L;
        String suffix = Long.toString(System.nanoTime());

        // 1. 実在文書を登録し、資産を要員Aに貸与する
        Asset asset = Asset.builder()
                .assetTag("AST-DOCLINK-SCOPE-001")
                .assetName("Evidence Test MacBook")
                .category("PC")
                .ownerCompanyId(100L)
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        Document evidence = new Document();
        evidence.setTenantId("default");
        evidence.setDocumentType("INTERNAL");
        evidence.setTitle("資産受渡証跡");
        evidence.setDirection("INTERNAL");
        evidence.setStatus("DRAFT");
        evidence.setCurrency("JPY");
        evidence.setLegalHoldFlag(0);
        evidence.setVersion(1L);
        documentMapper.insert(evidence);
        Long evidenceDocId = evidence.getId();
        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", engineerAId,
                LocalDate.now(), LocalDate.now().plusMonths(1), evidenceDocId, "受領書添付", 1L);
        assertThat(assignment.getHandoverEvidenceDocId()).isEqualTo(evidenceDocId);

        // 2. t_document_link に ASSET_ASSIGNMENT リンクが登録されていることを検証
        List<Long> linkedDocIds = documentLinkMapper.findDocumentIdsByTarget("ASSET_ASSIGNMENT", assignment.getId());
        assertThat(linkedDocIds).contains(evidenceDocId);

        List<DocumentLink> links = documentLinkMapper.findByDocumentId(evidenceDocId);
        assertThat(links).isNotEmpty();
        assertThat(links.get(0).getTargetType()).isEqualTo("ASSET_ASSIGNMENT");
        assertThat(links.get(0).getTargetId()).isEqualTo(assignment.getId());

        // 3. 要員Aユーザーを作成してリンク
        SysUser userEngA = SysUser.builder()
                .username("eng-doclink-8801-" + suffix)
                .password("pass")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(userEngA);
        engineerAccountLinkService.link(engineerAId, userEngA.getId(), 1L);

        // 4. 要員Aは DocumentLink 経由で証跡文書へアクセス可能（自己貸与中）
        assertThat(assetScopeServiceImpl.isAccessibleByDocumentLink(evidenceDocId, "要員", userEngA.getId()))
                .as("要員Aは自己貸与の証跡文書へアクセス可能").isTrue();

        // 5. 要員Bは DocumentLink 経由での証跡文書アクセスが拒否される（担当外）
        SysUser userEngB = SysUser.builder()
                .username("eng-doclink-8802-" + suffix)
                .password("pass")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(userEngB);
        engineerAccountLinkService.link(engineerBId, userEngB.getId(), 1L);

        assertThat(assetScopeServiceImpl.isAccessibleByDocumentLink(evidenceDocId, "要員", userEngB.getId()))
                .as("要員Bは他要員の貸与証跡文書へアクセス不可（Fail-Closed）").isFalse();

        // Document APIも実在Document -> DocumentLink -> assignment -> assetの認可を通る。
        mockMvc.perform(get("/api/documents/" + evidenceDocId)
                        .with(SecurityMockMvcRequestPostProcessors.user(userEngA.getUsername()).roles("要員")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/" + evidenceDocId)
                        .with(SecurityMockMvcRequestPostProcessors.user(userEngB.getUsername()).roles("要員")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/documents/" + evidenceDocId + "/versions/1/download")
                        .with(SecurityMockMvcRequestPostProcessors.user(userEngB.getUsername()).roles("要員")))
                .andExpect(status().isForbidden());

        // 6. 管理者は DocumentLink 経由でも全件アクセス可能
        assertThat(assetScopeServiceImpl.isAccessibleByDocumentLink(evidenceDocId, "管理者", 1L))
                .as("管理者は常に証跡文書へアクセス可能").isTrue();

        // 7. 返却時の証跡文書も t_document_link に登録される
        Document returnEvidence = new Document();
        returnEvidence.setTenantId("default");
        returnEvidence.setDocumentType("INTERNAL");
        returnEvidence.setTitle("資産返却証跡");
        returnEvidence.setDirection("INTERNAL");
        returnEvidence.setStatus("DRAFT");
        returnEvidence.setCurrency("JPY");
        returnEvidence.setLegalHoldFlag(0);
        returnEvidence.setVersion(1L);
        documentMapper.insert(returnEvidence);
        Long returnDocId = returnEvidence.getId();
        AssetAssignment returned = assetAssignmentService.returnAssignment(
                assignment.getId(), LocalDate.now(), returnDocId, "返却受領書添付", 1L);
        assertThat(returned.getReturnEvidenceDocId()).isEqualTo(returnDocId);

        List<Long> allLinkedDocs = documentLinkMapper.findDocumentIdsByTarget("ASSET_ASSIGNMENT", assignment.getId());
        assertThat(allLinkedDocs).contains(evidenceDocId, returnDocId);
        // 返却後も旧assignmentの本人には自分の受領証跡だけを再表示できるが、他要員へ継承しない。
        assertThat(assetScopeServiceImpl.isAccessibleByDocumentLink(evidenceDocId, "要員", userEngA.getId())).isTrue();
        mockMvc.perform(get("/api/documents/" + evidenceDocId)
                        .with(SecurityMockMvcRequestPostProcessors.user(userEngA.getUsername()).roles("要員")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/" + evidenceDocId)
                        .with(SecurityMockMvcRequestPostProcessors.user(userEngB.getUsername()).roles("要員")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/documents/" + evidenceDocId + "/versions/1/download")
                        .with(SecurityMockMvcRequestPostProcessors.user(userEngB.getUsername()).roles("要員")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Boundary 8: 論理削除安全条件 (Soft Delete Invariants) — ACTIVE貸与・未失効アカウント・未解放ライセンスは論理削除不可")
    void testSoftDeleteInvariants() {
        // 1. ACTIVE貸与中の資産は論理削除できない
        Asset asset = Asset.builder()
                .assetTag("AST-SOFTDEL-001")
                .assetName("Soft Delete Test PC")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 8801L,
                LocalDate.now(), LocalDate.now().plusMonths(1), null, "貸与", 1L);

        // ACTIVE貸与が存在する状態での論理削除は Business Exception で拒否される
        assertThatThrownBy(() -> assetService.softDeleteAsset(asset.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未返却貸与");

        // 2. 未失効アカウントは論理削除できない
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("SOFTDEL_SYSTEM_" + System.nanoTime())
                .systemName("Soft Delete Test System")
                .systemType("SAAS_MAIL")
                .build();
        externalAccountSystemMapper.insert(system);

        ExternalAccountReference ref = externalAccountService.createAccountReference(
                system.getId(), "softdel@test.jp", "ENGINEER", 8801L, "MEMBER", 1L);
        assertThat(ref.getStatus()).isEqualTo("ACTIVE");

        // ACTIVE状態のアカウントは論理削除不可
        assertThatThrownBy(() -> externalAccountService.softDeleteAccount(ref.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACTIVE");

        // REVOKED終端行も論理削除せず、履歴を保持する
        externalAccountService.confirmRevoke(ref.getId(), 1L);
        assertThatThrownBy(() -> externalAccountService.softDeleteAccount(ref.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("終端履歴");

        // RELEASED終端行も論理削除せず、席数・履歴の根拠を保持する
        LicensePlan terminalPlan = LicensePlan.builder()
                .planCode("LIC-SOFTDEL-TERMINAL-" + System.nanoTime())
                .planName("Soft Delete Terminal License")
                .seatLimit(1)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(terminalPlan, 1L);
        LicenseAssignment releasedLicense = licenseService.assignLicense(
                terminalPlan.getId(), "ENGINEER", 8801L, ref.getId(), LocalDate.now(), 1L);
        licenseService.releaseLicense(releasedLicense.getId(), LocalDate.now(), 1L);
        assertThatThrownBy(() -> licenseService.softDeleteAssignment(releasedLicense.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("終端履歴");

        // 3. 資産廃棄は deleted_flag ではなく DISPOSED 状態遷移で表現する
        Asset asset2 = Asset.builder()
                .assetTag("AST-DISPOSED-001")
                .assetName("Disposed Test PC")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset2, 1L);

        // DISPOSED 遷移 + イベント記録
        assetService.disposeAsset(asset2.getId(), "廃棄処分", 1L);
        Asset disposed = assetService.getById(asset2.getId());
        assertThat(disposed.getStatus()).isEqualTo("DISPOSED");
        assertThat(disposed.getDeletedFlag()).as("DISPOSED資産はdeleted_flag=0のまま履歴を保持").isEqualTo(0);

        // t_asset_event に廃棄イベントが追記されている
        Long disposeEventCount = assetEventMapper.selectCount(new LambdaQueryWrapper<AssetEvent>()
                .eq(AssetEvent::getAssetId, asset2.getId())
                .eq(AssetEvent::getEventType, "DISPOSED"));
        assertThat(disposeEventCount).isGreaterThanOrEqualTo(1);
    }
}
