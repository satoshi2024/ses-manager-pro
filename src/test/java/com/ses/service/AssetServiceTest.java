package com.ses.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.BaseIntegrationTest;
import com.ses.common.exception.BusinessException;
import com.ses.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Asset / Assignment / Inventory / License Service Tests")
class AssetServiceTest extends BaseIntegrationTest {

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentService assetAssignmentService;

    @Autowired
    private AssetEventService assetEventService;

    @Autowired
    private AssetInventoryService assetInventoryService;

    @Autowired
    private ExternalAccountService externalAccountService;

    @Autowired
    private LicenseService licenseService;

    @Test
    @DisplayName("Asset Lifecycle: create -> assign -> return -> dispose")
    void testAssetFullLifecycle() {
        // 1. 資産作成
        Asset asset = Asset.builder()
                .assetTag("AST-FLOW-001")
                .assetName("Dell Latitude 5530")
                .category("PC")
                .status("IN_STOCK")
                .purchasePrice(new BigDecimal("120000.00"))
                .build();
        assetService.createAsset(asset, 1L);
        assertThat(asset.getId()).isNotNull();

        // イベントが記録されていること
        List<AssetEvent> events1 = assetEventService.getEventsByAssetId(asset.getId());
        assertThat(events1).hasSize(1);
        assertThat(events1.get(0).getEventType()).isEqualTo("CREATED");

        // 2. 貸与
        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(),
                "ENGINEER",
                100L,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 9, 30),
                null,
                "新入社員貸与",
                1L
        );
        assertThat(assignment.getId()).isNotNull();

        Asset assignedAsset = assetService.getById(asset.getId());
        assertThat(assignedAsset.getStatus()).isEqualTo("ASSIGNED");

        // 貸与中の再貸与は拒否されること
        assertThatThrownBy(() -> assetAssignmentService.createAssignment(
                asset.getId(),
                "ENGINEER",
                200L,
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 8, 31),
                null,
                "二重貸与試行",
                1L
        )).isInstanceOf(BusinessException.class);

        // 3. 返却
        AssetAssignment returned = assetAssignmentService.returnAssignment(
                assignment.getId(),
                LocalDate.now(),
                null,
                "退職に伴う返却",
                1L
        );
        assertThat(returned.getStatus()).isEqualTo("RETURNED");

        Asset inStockAsset = assetService.getById(asset.getId());
        assertThat(inStockAsset.getStatus()).isEqualTo("IN_STOCK");

        // 4. 廃棄
        Asset disposed = assetService.disposeAsset(asset.getId(), "リース満了廃棄", 1L, null);
        assertThat(disposed.getStatus()).isEqualTo("DISPOSED");

        Asset reserved = Asset.builder()
                .assetTag("AST-RESERVED-001")
                .assetName("Reserved device")
                .category("PC")
                .build();
        assetService.createAsset(reserved, 1L);
        assertThat(assetService.changeStatus(reserved.getId(), "reserved", "予約", 1L, null).getStatus())
                .isEqualTo("RESERVED");
        assertThatThrownBy(() -> assetService.changeStatus(reserved.getId(), "NOT_A_REAL_STATUS", "不正", 1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("資産ステータスが不正です");

        // 廃棄後の再貸与は拒否されること
        assertThatThrownBy(() -> assetAssignmentService.createAssignment(
                asset.getId(),
                "ENGINEER",
                300L,
                LocalDate.now(),
                null,
                null,
                "廃棄資産貸与試行",
                1L
        )).isInstanceOf(BusinessException.class);

        // 全イベント履歴の確認
        List<AssetEvent> allEvents = assetEventService.getEventsByAssetId(asset.getId());
        assertThat(allEvents).hasSize(4); // CREATED, ASSIGNED, RETURNED, DISPOSED
    }

    @Test
    @DisplayName("Asset status transitions reject forbidden resurrection and assignment shortcuts")
    void testAssetStatusTransitionGuard() {
        Asset inStock = Asset.builder()
                .assetTag("AST-TRANSITION-IN-STOCK-" + System.nanoTime())
                .assetName("Transition in stock")
                .category("PC")
                .build();
        assetService.createAsset(inStock, 1L);
        assertThatThrownBy(() -> assetService.changeStatus(
                inStock.getId(), "ASSIGNED", "貸与行なしの直接変更", 1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("貸与・返却は専用の貸与サービス");
        assertThat(assetService.getById(inStock.getId()).getStatus()).isEqualTo("IN_STOCK");

        Asset disposed = Asset.builder()
                .assetTag("AST-TRANSITION-DISPOSED-" + System.nanoTime())
                .assetName("Transition disposed")
                .category("PC")
                .build();
        assetService.createAsset(disposed, 1L);
        assetService.disposeAsset(disposed.getId(), "廃棄", 1L, null);
        assertThatThrownBy(() -> assetService.changeStatus(
                disposed.getId(), "RESERVED", "廃棄済み資産の復活", 1L, null))
                .isInstanceOf(BusinessException.class);
        assertThat(assetService.getById(disposed.getId()).getStatus()).isEqualTo("DISPOSED");

        Asset lost = Asset.builder()
                .assetTag("AST-TRANSITION-LOST-" + System.nanoTime())
                .assetName("Transition lost")
                .category("PC")
                .build();
        assetService.createAsset(lost, 1L);
        assetService.reportLost(lost.getId(), "紛失", 1L, null);
        assertThatThrownBy(() -> assetService.changeStatus(
                lost.getId(), "UNDER_MAINTENANCE", "紛失資産の修理戻し", 1L, null))
                .isInstanceOf(BusinessException.class);

        Asset maintenance = Asset.builder()
                .assetTag("AST-TRANSITION-MAINT-" + System.nanoTime())
                .assetName("Transition maintenance")
                .category("PC")
                .status("UNDER_MAINTENANCE")
                .build();
        assetService.createAsset(maintenance, 1L);
        assertThatThrownBy(() -> assetService.changeStatus(
                maintenance.getId(), "ASSIGNED", "保守中の直接貸与", 1L, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> assetService.disposeAsset(
                maintenance.getId(), "保守中の廃棄", 1L, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Asset Inventory Run: start -> item check -> complete with reconciliation counts")
    void testInventoryRunFlow() {
        // テスト用資産を作成
        Asset a1 = Asset.builder().assetTag("AST-INV-001").assetName("Display A").category("MONITOR").status("IN_STOCK").location("東京本社").build();
        Asset a2 = Asset.builder().assetTag("AST-INV-002").assetName("Display B").category("MONITOR").status("IN_STOCK").location("大阪支社").build();
        assetService.createAsset(a1, 1L);
        assetService.createAsset(a2, 1L);

        // 棚卸し開始
        AssetInventoryRun run = assetInventoryService.startInventoryRun("INV-TEST-2026", "2026上期棚卸し", LocalDate.now(), 1L);
        assertThat(run.getId()).isNotNull();
        assertThat(run.getTotalAssets()).isGreaterThanOrEqualTo(2);

        List<AssetInventoryItem> items = assetInventoryService.getItemsByRunId(run.getId());
        assertThat(items).isNotEmpty();

        // 1件目を MATCH、2件目を DISCREPANCY で記録
        AssetInventoryItem item1 = items.get(0);
        assetInventoryService.recordItemCheck(item1.getId(), "IN_STOCK", "東京本社", "MATCH", "確認完了", null, 1L);

        if (items.size() > 1) {
            AssetInventoryItem item2 = items.get(1);
            assetInventoryService.recordItemCheck(item2.getId(), "IN_STOCK", "名古屋営業所", "DISCREPANCY", "場所異動未届出", "台帳場所更新", 1L);
        }

        // 棚卸し完了
        AssetInventoryRun completed = assetInventoryService.completeInventoryRun(run.getId(), 1L);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getMatchedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Inventory observed status accepts and normalizes all six asset states")
    void testInventoryObservedStatusVocabulary() {
        Asset asset = Asset.builder()
                .assetTag("AST-INV-STATUS-" + System.nanoTime())
                .assetName("Inventory status device")
                .category("PC")
                .build();
        assetService.createAsset(asset, 1L);

        AssetInventoryRun run = assetInventoryService.startInventoryRun(
                "INV-STATUS-" + System.nanoTime(), "状態語彙確認", LocalDate.now(), 1L);
        AssetInventoryItem item = assetInventoryService.getItemsByRunId(run.getId()).stream()
                .filter(candidate -> asset.getId().equals(candidate.getAssetId()))
                .findFirst()
                .orElseThrow();

        for (String status : List.of(
                "IN_STOCK", "ASSIGNED", "UNDER_MAINTENANCE", "LOST", "DISPOSED", "RESERVED")) {
            String input = "RESERVED".equals(status) ? " reserved " : status;
            AssetInventoryItem checked = assetInventoryService.recordItemCheck(
                    item.getId(), input, "棚卸し場所", "MATCH", null, null, 1L);
            assertThat(checked.getObservedStatus()).isEqualTo(status);
        }
        assertThatThrownBy(() -> assetInventoryService.recordItemCheck(
                item.getId(), "NOT_A_REAL_STATUS", "棚卸し場所", "MATCH", null, null, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("実地確認ステータスが不正です");
    }

    @Test
    @DisplayName("License Plan & Assignment: assign, limit exceeded, release")
    void testLicensePlanAndAssignment() {
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-SLACK-TEST")
                .planName("Slack Enterprise")
                .seatLimit(1)
                .allocatedCount(0)
                .costPerSeat(new BigDecimal("1500.00"))
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);

        // 1席目割当 (成功)
        LicenseAssignment a1 = licenseService.assignLicense(plan.getId(), "ENGINEER", 501L, null, LocalDate.now(), 1L);
        assertThat(a1.getId()).isNotNull();

        // 2席目割当 (上限1席のため拒否)
        assertThatThrownBy(() -> licenseService.assignLicense(plan.getId(), "ENGINEER", 502L, null, LocalDate.now(), 1L))
                .isInstanceOf(BusinessException.class);

        // 1席目解放 (成功)
        LicenseAssignment released = licenseService.releaseLicense(a1.getId(), LocalDate.now(), 1L);
        assertThat(released.getStatus()).isEqualTo("RELEASED");

        // 解放後に再度割当可能であること
        LicenseAssignment a2 = licenseService.assignLicense(plan.getId(), "ENGINEER", 502L, null, LocalDate.now(), 1L);
        assertThat(a2.getId()).isNotNull();
    }

    @Test
    @DisplayName("External Account Reference: register, search, confirm revoke")
    void testExternalAccountReferenceFlow() {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("GOOGLE_TEST")
                .systemName("Google Workspace")
                .systemType("SAAS_MAIL")
                .isActive(1)
                .build();
        externalAccountService.saveSystem(system);

        ExternalAccountReference ref = externalAccountService.registerAccountReference(
                system.getId(),
                "test.engineer@ses-test.jp",
                "ENGINEER",
                601L,
                "MEMBER",
                1L
        );
        assertThat(ref.getId()).isNotNull();
        assertThat(ref.getStatus()).isEqualTo("ACTIVE");

        // 検索
        IPage<ExternalAccountReference> page = externalAccountService.searchAccounts(1, 10, system.getId(), "ENGINEER", 601L, "ACTIVE");
        assertThat(page.getRecords()).hasSize(1);

        // 失効完了確認
        ExternalAccountReference revoked = externalAccountService.confirmRevoke(ref.getId(), 1L);
        assertThat(revoked.getStatus()).isEqualTo("REVOKED");
        assertThat(revoked.getRevokeConfirmedAt()).isNotNull();
    }
}
