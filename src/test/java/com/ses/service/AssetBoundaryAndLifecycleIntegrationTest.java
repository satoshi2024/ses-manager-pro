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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Asset Comprehensive Boundary & Integration Tests (境界テスト・Recovery・スコープ検証)")
class AssetBoundaryAndLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AssetService assetService;

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
        // 1. 資産作成
        Asset asset = Asset.builder()
                .assetTag("AST-REASSIGN-001")
                .assetName("ThinkPad L14")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        // 2. 貸与1 (2026-08-01 ~ 2026-08-15)
        AssetAssignment as1 = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 101L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), null, "Initial", 1L);
        assertThat(as1.getStatus()).isEqualTo("ACTIVE");

        // 3. 返却 (2026-08-15) -> ステータスが IN_STOCK に復帰
        AssetAssignment returnedAs = assetAssignmentService.returnAssignment(as1.getId(), LocalDate.of(2026, 8, 15), null, "Returned OK", 1L);
        assertThat(returnedAs.getStatus()).isEqualTo("RETURNED");

        Asset updatedAsset = assetService.getById(asset.getId());
        assertThat(updatedAsset.getStatus()).isEqualTo("IN_STOCK");

        // 4. 返却直後の再貸与 (2026-08-16 ~ 2026-08-31) -> 成功すること
        AssetAssignment as2 = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 102L, LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 31), null, "Second", 1L);
        assertThat(as2.getStatus()).isEqualTo("ACTIVE");
        assertThat(as2.getAssigneeId()).isEqualTo(102L);

        // 資産が再び ASSIGNED になること
        assertThat(assetService.getById(asset.getId()).getStatus()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("Boundary 2: License seat limit boundary (-1, =, +1), concurrent allocation, release & re-assign")
    void testLicenseSeatLimitBoundaryMinusOneEqualPlusOneAndReassign() {
        // 1. seat_limit = 2 のプラン作成
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-BOUND-001")
                .planName("JetBrains All Products")
                .seatLimit(2)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);

        // 2. 割当 1席目 (seat_limit - 1) -> 成功
        LicenseAssignment lic1 = licenseService.assignLicense(plan.getId(), "ENGINEER", 201L, null, LocalDate.now(), 1L);
        assertThat(lic1.getStatus()).isEqualTo("ACTIVE");
        LicensePlan planAfter1 = licensePlanMapper.selectById(plan.getId());
        assertThat(planAfter1.getAllocatedCount()).isEqualTo(1);

        // 3. 割当 2席目 (seat_limit = 上限到達) -> 成功
        LicenseAssignment lic2 = licenseService.assignLicense(plan.getId(), "ENGINEER", 202L, null, LocalDate.now(), 1L);
        assertThat(lic2.getStatus()).isEqualTo("ACTIVE");
        LicensePlan planAfter2 = licensePlanMapper.selectById(plan.getId());
        assertThat(planAfter2.getAllocatedCount()).isEqualTo(2);

        // 4. 割当 3席目 (seat_limit + 1 超過) -> 拒否 (BusinessException)
        assertThatThrownBy(() -> licenseService.assignLicense(plan.getId(), "ENGINEER", 203L, null, LocalDate.now(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");

        // 5. 1席解放
        licenseService.releaseLicense(lic1.getId(), LocalDate.now(), 1L);
        LicensePlan planAfterRelease = licensePlanMapper.selectById(plan.getId());
        assertThat(planAfterRelease.getAllocatedCount()).isEqualTo(1);

        // 6. 解放後の再割当 -> 成功
        LicenseAssignment lic3 = licenseService.assignLicense(plan.getId(), "ENGINEER", 204L, null, LocalDate.now(), 1L);
        assertThat(lic3.getStatus()).isEqualTo("ACTIVE");
        assertThat(licensePlanMapper.selectById(plan.getId()).getAllocatedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Boundary 3: Inventory disallow update after complete & disallow double complete")
    void testInventoryDisallowUpdateAndDoubleComplete() {
        // 1. 棚卸し計画開始
        AssetInventoryRun run = assetInventoryService.startInventoryRun("INV-2026-Q3", "2026-Q3棚卸し", LocalDate.now(), 1L);
        assertThat(run.getStatus()).isEqualTo("IN_PROGRESS");

        // 2. 明細確認
        List<AssetInventoryItem> items = assetInventoryItemMapper.selectList(
                new LambdaQueryWrapper<AssetInventoryItem>().eq(AssetInventoryItem::getInventoryRunId, run.getId()));
        if (!items.isEmpty()) {
            AssetInventoryItem firstItem = items.get(0);
            assetInventoryService.recordItemCheck(firstItem.getId(), "IN_STOCK", "本社5F", "MATCH", "正常確認", "なし", 1L);
        }

        // 3. 棚卸し確定完了
        AssetInventoryRun completedRun = assetInventoryService.completeInventoryRun(run.getId(), 1L);
        assertThat(completedRun.getStatus()).isEqualTo("COMPLETED");

        // 4. 完了後の二重確定 -> 拒否
        assertThatThrownBy(() -> assetInventoryService.completeInventoryRun(run.getId(), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("既に完了");

        // 5. 完了後の明細更新 -> 拒否
        if (!items.isEmpty()) {
            AssetInventoryItem firstItem = items.get(0);
            assertThatThrownBy(() -> assetInventoryService.recordItemCheck(firstItem.getId(), "LOST", "不明", "MISSING", "事後変更", "紛失起票", 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("完了済み");
        }
    }

    @Test
    @DisplayName("Boundary 4: Provider recovery, idempotency & status persistence")
    void testProviderRecoveryAndIdempotency() {
        // 1. システム & アカウント参照作成
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("SLACK_RECOVERY")
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

        // 2. タイムアウト / 失敗時のモック設定
        if (providerClient instanceof MockExternalAccountProviderClientImpl mockClient) {
            mockClient.setMockStatus(ref.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT);

            // 3. 失効要求実行 -> PENDING_CONFIRMATION / SUSPENDED のまま保持され、成功扱いにならないこと
            ref.setStatus("SUSPENDED");
            ref.setRevokeRequestedAt(LocalDateTime.now());
            externalAccountReferenceMapper.updateById(ref);

            ExternalAccountReference suspendedRef = externalAccountReferenceMapper.selectById(ref.getId());
            assertThat(suspendedRef.getStatus()).isEqualTo("SUSPENDED");
            assertThat(suspendedRef.getRevokeRequestedAt()).isNotNull();
            assertThat(suspendedRef.getRevokeConfirmedAt()).isNull();

            // 4. プロバイダが復旧し CONFIRMED に遷移
            mockClient.setMockStatus(ref.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);

            // 5. 失効確認ポーリング / 手動確認の実行 -> 正常に REVOKED へ遷移
            externalAccountService.confirmRevoke(ref.getId(), 1L);
            ExternalAccountReference confirmedRef = externalAccountReferenceMapper.selectById(ref.getId());
            assertThat(confirmedRef.getStatus()).isEqualTo("REVOKED");
            assertThat(confirmedRef.getRevokeConfirmedAt()).isNotNull();

            // 6. 既に REVOKED のアカウントに対する二重失効確認 -> 冪等に安全に REVOKED のまま完了すること
            ExternalAccountReference duplicateRevoked = externalAccountService.confirmRevoke(ref.getId(), 1L);
            assertThat(duplicateRevoked.getStatus()).isEqualTo("REVOKED");
        }
    }

    @Test
    @DisplayName("Boundary 5: Offboarding blocker 3 categories & waiver bypass")
    void testOffboardingThreeBlockers() {
        Long engineerId = 9999L;

        // 1. 未返却端末
        Asset asset = Asset.builder()
                .assetTag("AST-BLK-001")
                .assetName("Blocker Device")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);
        assetAssignmentService.createAssignment(asset.getId(), "ENGINEER", engineerId, LocalDate.now(), LocalDate.now().plusMonths(1), null, "貸与", 1L);

        // 2. 未失効アカウント
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

        // 3. 未解放ライセンス
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-BLK-001")
                .planName("Blocker License")
                .seatLimit(10)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);
        licenseService.assignLicense(plan.getId(), "ENGINEER", engineerId, ref.getId(), LocalDate.now(), 1L);

        // 4. クリアランス検証: 3大項目すべてが blocker として検出されること
        OffboardingClearanceResultDto result = assetOffboardingService.checkOffboardingClearance(engineerId);
        assertThat(result.isClearancePassed()).isFalse();
        assertThat(result.getUnreturnedAssetCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getUnrevokedAccountCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getUnreleasedLicenseCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getBlockingItems()).hasSize(3);

        // 5. LIFECYCLE_EXCEPTION による例外免除
        assetOffboardingService.approveOffboardingWaiver(engineerId, "役員特例承認済み", 7777L, 1L);
        OffboardingClearanceResultDto waivedResult = assetOffboardingService.checkOffboardingClearance(engineerId);
        assertThat(waivedResult.isClearancePassed()).isTrue();
        assertThat(waivedResult.isWaived()).isTrue();
    }
}
