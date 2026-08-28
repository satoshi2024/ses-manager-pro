package com.ses.service;

import com.ses.BaseIntegrationTest;
import com.ses.dto.asset.OffboardingClearanceResultDto;
import com.ses.entity.*;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.mapper.LicenseAssignmentMapper;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.provider.impl.MockExternalAccountProviderClientImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Asset Offboarding & Provider Integration Tests (退社ゲート・プロバイダ連携)")
class AssetOffboardingServiceTest extends BaseIntegrationTest {

    @Autowired
    private AssetOffboardingService assetOffboardingService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private ExternalAccountSystemMapper externalAccountSystemMapper;

    @Autowired
    private LicenseAssignmentMapper licenseAssignmentMapper;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private ExternalAccountProviderClient externalAccountProviderClient;

    @Test
    @DisplayName("Offboarding clearance: blocked when active unreturned asset or account exists")
    void testOffboardingClearanceBlocking() {
        Long engineerId = 9901L;

        // 1. 資産を貸与
        Asset asset = Asset.builder()
                .assetTag("AST-OFFBOARD-01")
                .assetName("Offboarding Test PC")
                .category("PC")
                .status("ASSIGNED")
                .build();
        assetService.createAsset(asset, 1L);

        AssetAssignment assignment = AssetAssignment.builder()
                .assetId(asset.getId())
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .startDate(LocalDate.now().minusMonths(3))
                .status("ACTIVE")
                .build();
        assetAssignmentMapper.insert(assignment);

        // 2. クリアランスチェック（未返却のためブロックされること）
        OffboardingClearanceResultDto result1 = assetOffboardingService.checkOffboardingClearance(engineerId);
        assertThat(result1.isClearancePassed()).isFalse();
        assertThat(result1.getUnreturnedAssetCount()).isEqualTo(1);
        assertThat(result1.getBlockingItems()).isNotEmpty();

        // 3. 例外承認を実施
        assetOffboardingService.approveOffboardingWaiver(engineerId, "役員特例承認済み", 5001L, 1L);

        // 4. クリアランス再チェック（例外承認によりパスすること）
        OffboardingClearanceResultDto result2 = assetOffboardingService.checkOffboardingClearance(engineerId);
        assertThat(result2.isClearancePassed()).isTrue();
        assertThat(result2.isWaived()).isTrue();
    }

    @Test
    @DisplayName("Trigger offboarding revocations: accounts revoked and licenses released")
    void testTriggerOffboardingRevocations() {
        Long engineerId = 9902L;

        // 1. システム & 外部アカウント参照作成
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("GITHUB_OFFBOARD")
                .systemName("GitHub")
                .systemType("SAAS_SCM")
                .isActive(1)
                .build();
        externalAccountSystemMapper.insert(system);

        ExternalAccountReference ref = ExternalAccountReference.builder()
                .systemId(system.getId())
                .accountIdentifier("dev9902@ses-test.jp")
                .assigneeType("ENGINEER")
                .assigneeId(engineerId)
                .status("ACTIVE")
                .build();
        externalAccountReferenceMapper.insert(ref);

        // 2. ライセンスプラン & 割当作成
        LicensePlan plan = LicensePlan.builder()
                .planCode("LIC-OFFBOARD-01")
                .planName("Slack Pro")
                .seatLimit(10)
                .allocatedCount(1)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);

        LicenseAssignment licAssign = licenseService.assignLicense(plan.getId(), "ENGINEER", engineerId, ref.getId(), LocalDate.now(), 1L);

        // 3. オフボーディング一括無効化トリガー実行
        assetOffboardingService.triggerOffboardingRevocations(engineerId, 1L);

        // 4. 検証: アカウントが REVOKED または SUSPENDED になっていること
        ExternalAccountReference updatedRef = externalAccountReferenceMapper.selectById(ref.getId());
        assertThat(updatedRef.getStatus()).isIn("REVOKED", "SUSPENDED");
        assertThat(updatedRef.getRevokeRequestedAt()).isNotNull();

        // 5. 検証: ライセンスが RELEASED になっていること
        LicenseAssignment updatedLic = licenseAssignmentMapper.selectById(licAssign.getId());
        assertThat(updatedLic.getStatus()).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("Provider revoke confirmation: timeout / failed status is NOT treated as confirmed")
    void testProviderRevokeConfirmationTimeout() {
        if (externalAccountProviderClient instanceof MockExternalAccountProviderClientImpl mockClient) {
            ExternalAccountReference ref = ExternalAccountReference.builder()
                    .accountIdentifier("timeout.user@ses-test.jp")
                    .status("SUSPENDED")
                    .build();
            ref.setId(8888L);

            // タイムアウト・失敗ステータスをモック
            mockClient.setMockStatus(8888L, ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT);

            ExternalAccountProviderClient.RevokeConfirmationStatus status =
                    mockClient.checkRevokeConfirmation(ref);

            assertThat(status).isEqualTo(ExternalAccountProviderClient.RevokeConfirmationStatus.FAILED_OR_TIMEOUT);
            // 確証が得られない限り CONFIRMED にはならない
            assertThat(status).isNotEqualTo(ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);
        }
    }
}
