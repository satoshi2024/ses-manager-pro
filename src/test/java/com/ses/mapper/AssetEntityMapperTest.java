package com.ses.mapper;

import com.ses.BaseIntegrationTest;
import com.ses.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Asset / Assignment / Event / Account / License Entity & Mapper Tests")
class AssetEntityMapperTest extends BaseIntegrationTest {

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private AssetEventMapper assetEventMapper;

    @Autowired
    private AssetInventoryRunMapper assetInventoryRunMapper;

    @Autowired
    private AssetInventoryItemMapper assetInventoryItemMapper;

    @Autowired
    private ExternalAccountSystemMapper externalAccountSystemMapper;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private LicensePlanMapper licensePlanMapper;

    @Autowired
    private LicenseAssignmentMapper licenseAssignmentMapper;

    @Test
    @DisplayName("Asset CRUD & CAS status update")
    void testAssetCrudAndCas() {
        Asset asset = Asset.builder()
                .assetTag("AST-TEST-001")
                .serialNo("SN123456")
                .assetName("ThinkPad T14")
                .category("PC")
                .status("IN_STOCK")
                .purchasePrice(new BigDecimal("150000.00"))
                .build();
        assetMapper.insert(asset);
        assertThat(asset.getId()).isNotNull();

        Asset found = assetMapper.selectById(asset.getId());
        assertThat(found.getAssetTag()).isEqualTo("AST-TEST-001");
        assertThat(found.getVersion()).isEqualTo(0);

        // CAS 更新成功
        int updated = assetMapper.updateStatusWithCas(asset.getId(), "IN_STOCK", "ASSIGNED", 0);
        assertThat(updated).isEqualTo(1);

        Asset updatedAsset = assetMapper.selectById(asset.getId());
        assertThat(updatedAsset.getStatus()).isEqualTo("ASSIGNED");
        assertThat(updatedAsset.getVersion()).isEqualTo(1);

        // 期待バージョン不一致による CAS 失敗
        int failedUpdate = assetMapper.updateStatusWithCas(asset.getId(), "ASSIGNED", "IN_STOCK", 0);
        assertThat(failedUpdate).isEqualTo(0);
    }

    @Test
    @DisplayName("AssetAssignment overlap check query")
    void testAssetAssignmentOverlapQuery() {
        Asset asset = Asset.builder()
                .assetTag("AST-TEST-002")
                .assetName("MacBook Pro")
                .category("PC")
                .status("ASSIGNED")
                .build();
        assetMapper.insert(asset);

        AssetAssignment assignment = AssetAssignment.builder()
                .assetId(asset.getId())
                .assigneeType("ENGINEER")
                .assigneeId(101L)
                .startDate(LocalDate.of(2026, 4, 1))
                .expectedReturnDate(LocalDate.of(2026, 9, 30))
                .actualReturnDate(null) // 現在貸与中
                .status("ACTIVE")
                .build();
        assetAssignmentMapper.insert(assignment);

        // 重複期間のカウント: 2026-05-01 〜 2026-06-01 (重複あり -> 1)
        int overlap1 = assetAssignmentMapper.countOverlappingAssignments(
                asset.getId(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1), null);
        assertThat(overlap1).isEqualTo(1);

        // 過去の完了済み期間: 2026-01-01 〜 2026-03-31 (貸与開始前 -> 0)
        int overlap2 = assetAssignmentMapper.countOverlappingAssignments(
                asset.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), null);
        assertThat(overlap2).isEqualTo(0);

        // 自身のIDを除外した場合は 0
        int overlap3 = assetAssignmentMapper.countOverlappingAssignments(
                asset.getId(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1), assignment.getId());
        assertThat(overlap3).isEqualTo(0);
    }

    @Test
    @DisplayName("AssetEvent append-only logging")
    void testAssetEventLogging() {
        AssetEvent event = AssetEvent.builder()
                .assetId(1L)
                .eventType("CREATED")
                .eventTime(LocalDateTime.now())
                .actorUserId(1L)
                .fromStatus(null)
                .toStatus("IN_STOCK")
                .eventSummary("資産を新規登録しました")
                .build();
        assetEventMapper.insert(event);
        assertThat(event.getId()).isNotNull();

        List<AssetEvent> events = assetEventMapper.selectByAssetId(1L);
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getEventSummary()).isEqualTo("資産を新規登録しました");
    }

    @Test
    @DisplayName("LicensePlan seat limit CAS increment/decrement")
    void testLicensePlanCas() {
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-TEST-01")
                .planName("GitHub Enterprise")
                .seatLimit(2)
                .allocatedCount(0)
                .costPerSeat(new BigDecimal("3000.00"))
                .build();
        licensePlanMapper.insert(plan);

        // 1席目割当 (成功)
        int r1 = licensePlanMapper.incrementAllocatedCountWithCas(plan.getId(), 0);
        assertThat(r1).isEqualTo(1);

        // 2席目割当 (成功)
        int r2 = licensePlanMapper.incrementAllocatedCountWithCas(plan.getId(), 1);
        assertThat(r2).isEqualTo(1);

        // 3席目割当 (上限2席のためCAS失敗)
        int r3 = licensePlanMapper.incrementAllocatedCountWithCas(plan.getId(), 2);
        assertThat(r3).isEqualTo(0);

        // 1席返却 (成功)
        int r4 = licensePlanMapper.decrementAllocatedCountWithCas(plan.getId(), 2);
        assertThat(r4).isEqualTo(1);

        LicensePlan current = licensePlanMapper.selectById(plan.getId());
        assertThat(current.getAllocatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ExternalAccountReference confirm revoke CAS")
    void testExternalAccountConfirmRevoke() {
        ExternalAccountReference ref = ExternalAccountReference.builder()
                .systemId(1L)
                .accountIdentifier("dev-user@ses-test.jp")
                .assigneeType("ENGINEER")
                .assigneeId(201L)
                .status("ACTIVE")
                .build();
        externalAccountReferenceMapper.insert(ref);

        int updated = externalAccountReferenceMapper.confirmRevokeWithCas(
                ref.getId(), LocalDateTime.now(), 1L, 0);
        assertThat(updated).isEqualTo(1);

        ExternalAccountReference current = externalAccountReferenceMapper.selectById(ref.getId());
        assertThat(current.getStatus()).isEqualTo("REVOKED");
        assertThat(current.getRevokeConfirmedAt()).isNotNull();
        assertThat(current.getRevokeConfirmedBy()).isEqualTo(1L);
    }
}
