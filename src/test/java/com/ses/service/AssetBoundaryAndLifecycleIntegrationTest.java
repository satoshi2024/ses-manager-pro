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

@DisplayName("Asset Comprehensive Boundary & Integration Tests (境界・並行性・Recovery・スコープ検証)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
    private AssetScopeService assetScopeService;

    @Autowired
    private ExternalAccountProviderClient providerClient;

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

        Asset updatedAsset = assetService.getById(asset.getId());
        assertThat(updatedAsset.getStatus()).isEqualTo("IN_STOCK");

        AssetAssignment as2 = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 102L, LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 31), null, "Second", 1L);
        assertThat(as2.getStatus()).isEqualTo("ACTIVE");
        assertThat(as2.getAssigneeId()).isEqualTo(102L);
        assertThat(assetService.getById(asset.getId()).getStatus()).isEqualTo("ASSIGNED");
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

            // プロバイダ復旧
            mockClient.setMockStatus(ref.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);

            // ポーリングジョブ実行 -> 自動で REVOKED へ
            int processed = externalAccountService.processPendingRevokePollJob();
            assertThat(processed).isGreaterThanOrEqualTo(1);

            ExternalAccountReference confirmedRef = externalAccountReferenceMapper.selectById(ref.getId());
            assertThat(confirmedRef.getStatus()).isEqualTo("REVOKED");
            assertThat(confirmedRef.getRevokeConfirmedAt()).isNotNull();

            // 二重失効確認は安全に冪等処理されること
            ExternalAccountReference duplicateRevoked = externalAccountService.confirmRevoke(ref.getId(), 1L);
            assertThat(duplicateRevoked.getStatus()).isEqualTo("REVOKED");
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

        OffboardingClearanceResultDto result = assetOffboardingService.checkOffboardingClearance(engineerId);
        assertThat(result.isClearancePassed()).isFalse();
        assertThat(result.getUnreturnedAssetCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getUnrevokedAccountCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getUnreleasedLicenseCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getBlockingItems()).hasSize(3);

        assetOffboardingService.approveOffboardingWaiver(engineerId, "役員特例承認済み", 7777L, 1L);
        OffboardingClearanceResultDto waivedResult = assetOffboardingService.checkOffboardingClearance(engineerId);
        assertThat(waivedResult.isClearancePassed()).isTrue();
        assertThat(waivedResult.isWaived()).isTrue();
    }

    @Autowired
    private com.ses.mapper.DocumentLinkMapper documentLinkMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerAccountLinkService engineerAccountLinkService;

    @Test
    @DisplayName("Boundary 6: Multi-corporation & Organization Unit Scope Isolation (法人A/B・営業・要員分離)")
    void testOrganizationScopeAndMultiCorporationIsolation() {
        // 1. 法人A (companyId=10L) と 法人B (companyId=20L) の資産作成
        Asset assetCorpA = Asset.builder()
                .assetTag("AST-CORP-A-999")
                .assetName("Corp A MacBook Pro")
                .category("PC")
                .ownerCompanyId(10L)
                .status("IN_STOCK")
                .build();
        assetService.createAsset(assetCorpA, 1L);

        Asset assetCorpB = Asset.builder()
                .assetTag("AST-CORP-B-999")
                .assetName("Corp B ThinkPad")
                .category("PC")
                .ownerCompanyId(20L)
                .status("IN_STOCK")
                .build();
        assetService.createAsset(assetCorpB, 1L);

        // 2. 営業ユーザーと要員ユーザー作成
        SysUser userSalesA = SysUser.builder()
                .username("sales.user.a")
                .password("pass")
                .role("営業")
                .status(1)
                .build();
        sysUserMapper.insert(userSalesA);

        // 3. 管理者/HRは全資産を閲覧可能
        assertThat(assetScopeService.isAccessible(assetCorpA.getId(), "管理者", 1L)).isTrue();
        assertThat(assetScopeService.isAccessible(assetCorpB.getId(), "管理者", 1L)).isTrue();

        // 4. 要員A (engineerId=8801L) に貸与
        AssetAssignment asA = assetAssignmentService.createAssignment(
                assetCorpA.getId(), "ENGINEER", 8801L, LocalDate.now(), LocalDate.now().plusMonths(1), null, "貸与A", 1L);

        // 要員Aのユーザーとリンク
        SysUser userEngA = SysUser.builder()
                .username("eng8801")
                .password("pass")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(userEngA);
        engineerAccountLinkService.link(8801L, userEngA.getId(), 1L);

        // 要員Bのユーザー (engineerId=8802L)
        SysUser userEngB = SysUser.builder()
                .username("eng8802")
                .password("pass")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(userEngB);
        engineerAccountLinkService.link(8802L, userEngB.getId(), 1L);

        // 5. 要員スコープ検証: 要員Aは自己貸与資産のみ可視、他要員の貸与資産・未貸与資産は不可視
        assertThat(assetScopeService.isAccessible(assetCorpA.getId(), "要員", userEngA.getId())).isTrue();
        assertThat(assetScopeService.isAccessible(assetCorpB.getId(), "要員", userEngA.getId())).isFalse();
        assertThat(assetScopeService.isAccessible(assetCorpA.getId(), "要員", userEngB.getId())).isFalse();
    }

    @Test
    @DisplayName("Boundary 7: Document evidence scope & DocumentLink linkage verification")
    void testDocumentEvidenceScopeRejection() {
        Asset asset = Asset.builder()
                .assetTag("AST-DOCLINK-001")
                .assetName("Evidence Test MacBook")
                .category("PC")
                .ownerCompanyId(10L)
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        Long evidenceDocId = 9991L;

        // 1. 貸与（証跡DocId=9991Lを指定）
        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 8801L, LocalDate.now(), LocalDate.now().plusMonths(1), evidenceDocId, "受領書添付", 1L);
        assertThat(assignment.getHandoverEvidenceDocId()).isEqualTo(evidenceDocId);

        // 2. t_document_link に ASSET_ASSIGNMENT リンクが登録されていることを検証
        List<Long> linkedDocIds = documentLinkMapper.findDocumentIdsByTarget("ASSET_ASSIGNMENT", assignment.getId());
        assertThat(linkedDocIds).contains(evidenceDocId);

        List<DocumentLink> links = documentLinkMapper.findByDocumentId(evidenceDocId);
        assertThat(links).isNotEmpty();
        assertThat(links.get(0).getTargetType()).isEqualTo("ASSET_ASSIGNMENT");
        assertThat(links.get(0).getTargetId()).isEqualTo(assignment.getId());

        // 3. 返却時（返却証跡DocId=9992Lを指定）
        Long returnDocId = 9992L;
        AssetAssignment returned = assetAssignmentService.returnAssignment(
                assignment.getId(), LocalDate.now(), returnDocId, "返却受領書添付", 1L);
        assertThat(returned.getReturnEvidenceDocId()).isEqualTo(returnDocId);

        List<Long> returnLinkedDocs = documentLinkMapper.findDocumentIdsByTarget("ASSET_ASSIGNMENT", assignment.getId());
        assertThat(returnLinkedDocs).contains(evidenceDocId, returnDocId);

        // 4. 無関係な要員・担当外ユーザーからのアクセス拒否を検証
        SysUser foreignUser = SysUser.builder()
                .username("foreign.user")
                .password("pass")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(foreignUser);

        boolean isPermitted = assetScopeService.isAccessible(asset.getId(), "要員", foreignUser.getId());
        assertThat(isPermitted).isFalse();
    }
}
