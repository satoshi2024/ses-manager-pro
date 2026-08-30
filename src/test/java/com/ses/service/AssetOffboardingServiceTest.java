package com.ses.service;

import com.ses.BaseIntegrationTest;
import com.ses.dto.asset.OffboardingClearanceResultDto;
import com.ses.entity.*;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.mapper.LicenseAssignmentMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.provider.impl.MockExternalAccountProviderClientImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private LifecycleCaseMapper lifecycleCaseMapper;

    @Autowired
    private LifecycleTaskMapper lifecycleTaskMapper;

    @Autowired
    private LifecycleTemplateMapper lifecycleTemplateMapper;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private ExternalAccountProviderClient externalAccountProviderClient;

    @Autowired
    private ExternalAccountService externalAccountService;

    @Test
    @DisplayName("Offboarding clearance: blocked when active unreturned asset or account exists")
    void testOffboardingClearanceBlocking() {
        Long engineerId = 9901L;

        // 1. 資産を貸与
        Asset asset = Asset.builder()
                .assetTag("AST-OFFBOARD-01")
                .assetName("Offboarding Test PC")
                .category("PC")
                .status("IN_STOCK")
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
        LifecycleTemplate lifecycleTemplate = LifecycleTemplate.builder()
                .templateType("RESIGNATION")
                .name("退社資産scope test")
                .versionNo(1)
                .status("ACTIVE")
                .validFrom(LocalDate.now().minusDays(1))
                .build();
        lifecycleTemplateMapper.insert(lifecycleTemplate);
        LifecycleCase lifecycleCase = LifecycleCase.builder()
                .caseNo("LC-OFF-9901")
                .lifecycleType("RESIGNATION")
                .engineerId(engineerId)
                .templateId(lifecycleTemplate.getId())
                .templateVersion(1)
                .anchorDate(LocalDate.now())
                .status("ACTIVE")
                .title("退社scope test")
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

        OffboardingClearanceResultDto result1 = assetOffboardingService.checkOffboardingClearance(
                engineerId, lifecycleCase.getId(), lifecycleTask.getId());
        assertThat(result1.isClearancePassed()).isFalse();
        assertThat(result1.getUnreturnedAssetCount()).isEqualTo(1);
        assertThat(result1.getBlockingItems()).isNotEmpty();

        // 3. 承認済みLIFECYCLE_EXCEPTIONを対象要員へ紐付けて例外承認を実施
        ApprovalRequest approval = ApprovalRequest.builder()
                .requestNo("AR-OFF-9901")
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

        // 4. クリアランス再チェック（例外承認によりパスすること）
        OffboardingClearanceResultDto result2 = assetOffboardingService.checkOffboardingClearance(
                engineerId, lifecycleCase.getId(), lifecycleTask.getId());
        assertThat(result2.isClearancePassed()).isTrue();
        assertThat(result2.isWaived()).isTrue();

        OffboardingClearanceResultDto mismatchedScope = assetOffboardingService.checkOffboardingClearance(
                engineerId, lifecycleCase.getId(), lifecycleTask.getId() + 99999L);
        assertThat(mismatchedScope.isClearancePassed()).isFalse();
        assertThat(mismatchedScope.isWaived()).isFalse();
        assertThat(mismatchedScope.getUnreturnedAssetCount()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Provider poll: one confirmation exception is persisted and later accounts continue")
    void testProviderPollContinuesAfterConfirmationException() {
        if (!(externalAccountProviderClient instanceof MockExternalAccountProviderClientImpl mockClient)) {
            return;
        }
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("POLL_EXCEPTION_" + System.nanoTime())
                .systemName("Poll exception test")
                .systemType("IDP")
                .build();
        externalAccountSystemMapper.insert(system);
        ExternalAccountReference failed = ExternalAccountReference.builder()
                .systemId(system.getId()).accountIdentifier("poll-failed@ses-test.jp")
                .assigneeType("ENGINEER").assigneeId(9910L)
                .status("PENDING_CONFIRMATION").retryCount(0)
                .nextRetryAt(LocalDate.now().atStartOfDay()).build();
        ExternalAccountReference confirmed = ExternalAccountReference.builder()
                .systemId(system.getId()).accountIdentifier("poll-confirmed@ses-test.jp")
                .assigneeType("ENGINEER").assigneeId(9911L)
                .status("PENDING_CONFIRMATION").retryCount(0)
                .nextRetryAt(LocalDate.now().atStartOfDay()).build();
        externalAccountReferenceMapper.insert(failed);
        externalAccountReferenceMapper.insert(confirmed);
        mockClient.setConfirmationFailure(failed.getId(), new RuntimeException("simulated provider timeout"));
        mockClient.setMockStatus(confirmed.getId(), ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED);
        try {
            int processed = externalAccountProviderClient instanceof MockExternalAccountProviderClientImpl
                    ? externalAccountService.processPendingRevokePollJob() : 0;
            assertThat(processed).isGreaterThanOrEqualTo(1);
            ExternalAccountReference failedAfter = externalAccountReferenceMapper.selectById(failed.getId());
            ExternalAccountReference confirmedAfter = externalAccountReferenceMapper.selectById(confirmed.getId());
            assertThat(failedAfter.getStatus()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(failedAfter.getRetryCount()).isEqualTo(1);
            assertThat(failedAfter.getNextRetryAt()).isNotNull();
            assertThat(confirmedAfter.getStatus()).isEqualTo("REVOKED");
        } finally {
            mockClient.clearConfirmationFailure(failed.getId());
        }
    }
}
