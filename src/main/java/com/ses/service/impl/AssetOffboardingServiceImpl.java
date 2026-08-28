package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.asset.OffboardingClearanceResultDto;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.LicenseAssignment;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.LicenseAssignmentMapper;
import com.ses.service.AssetAssignmentService;
import com.ses.service.AssetOffboardingService;
import com.ses.service.ExternalAccountService;
import com.ses.service.LicenseService;
import com.ses.service.provider.ExternalAccountProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetOffboardingServiceImpl implements AssetOffboardingService {

    private final AssetAssignmentMapper assetAssignmentMapper;
    private final ExternalAccountReferenceMapper externalAccountReferenceMapper;
    private final LicenseAssignmentMapper licenseAssignmentMapper;
    private final AssetMapper assetMapper;
    private final AssetAssignmentService assetAssignmentService;
    private final ExternalAccountService externalAccountService;
    private final LicenseService licenseService;
    private final ExternalAccountProviderClient providerClient;

    // 例外免除メモリ台帳（承認ID・理由保持）
    private final Map<Long, WaiverRecord> waiverCache = new ConcurrentHashMap<>();

    private record WaiverRecord(Long engineerId, String reason, Long approvalRequestId, Long approvedBy, LocalDateTime approvedAt) {}

    @Override
    public OffboardingClearanceResultDto checkOffboardingClearance(Long engineerId) {
        if (engineerId == null) {
            return OffboardingClearanceResultDto.builder().clearancePassed(false).build();
        }

        // 1. 未返却の貸与資産（ACTIVE）
        List<AssetAssignment> activeAssignments = assetAssignmentMapper.selectActiveByAssignee("ENGINEER", engineerId);

        // 2. 未失効の外部アカウント（ACTIVE or SUSPENDED）
        List<ExternalAccountReference> activeAccounts = externalAccountReferenceMapper.selectActiveByAssignee("ENGINEER", engineerId);

        // 3. 未解放のライセンス（ACTIVE）
        List<LicenseAssignment> activeLicenses = licenseAssignmentMapper.selectActiveByAssignee("ENGINEER", engineerId);

        List<String> blockingItems = new ArrayList<>();
        for (AssetAssignment as : activeAssignments) {
            Asset asset = assetMapper.selectById(as.getAssetId());
            String tag = asset != null ? asset.getAssetTag() + " (" + asset.getAssetName() + ")" : "ID#" + as.getAssetId();
            blockingItems.add("未返却端末: " + tag + " [貸与ID: " + as.getId() + "]");
        }
        for (ExternalAccountReference acc : activeAccounts) {
            blockingItems.add("未失効外部アカウント: " + acc.getAccountIdentifier() + " (System#" + acc.getSystemId() + ")");
        }
        for (LicenseAssignment lic : activeLicenses) {
            blockingItems.add("未解放有償ライセンス: Plan#" + lic.getPlanId() + " [割当ID: " + lic.getId() + "]");
        }

        boolean hasWaiver = waiverCache.containsKey(engineerId);
        boolean passed = blockingItems.isEmpty() || hasWaiver;

        return OffboardingClearanceResultDto.builder()
                .clearancePassed(passed)
                .unreturnedAssetCount(activeAssignments.size())
                .unrevokedAccountCount(activeAccounts.size())
                .unreleasedLicenseCount(activeLicenses.size())
                .waived(hasWaiver)
                .blockingItems(blockingItems)
                .build();
    }

    @Override
    @Transactional
    public void triggerOffboardingRevocations(Long engineerId, Long actorUserId) {
        log.info("Triggering offboarding revocations for engineerId={}, actorUserId={}", engineerId, actorUserId);

        // 1. 外部アカウントの失効要求
        List<ExternalAccountReference> activeAccounts = externalAccountReferenceMapper.selectActiveByAssignee("ENGINEER", engineerId);
        for (ExternalAccountReference acc : activeAccounts) {
            acc.setStatus("SUSPENDED");
            acc.setRevokeRequestedAt(LocalDateTime.now());
            externalAccountReferenceMapper.updateById(acc);

            // プロバイダへ失効送信
            boolean requested = providerClient.requestRevoke(acc);
            if (requested) {
                ExternalAccountProviderClient.RevokeConfirmationStatus conf = providerClient.checkRevokeConfirmation(acc);
                if (conf == ExternalAccountProviderClient.RevokeConfirmationStatus.CONFIRMED) {
                    externalAccountReferenceMapper.confirmRevokeWithCas(acc.getId(), LocalDateTime.now(), actorUserId, acc.getVersion() + 1);
                }
            }
        }

        // 2. 有償ライセンスの一括解放
        List<LicenseAssignment> activeLicenses = licenseAssignmentMapper.selectActiveByAssignee("ENGINEER", engineerId);
        for (LicenseAssignment lic : activeLicenses) {
            licenseService.releaseLicense(lic.getId(), LocalDate.now(), actorUserId);
        }

        log.info("Offboarding revocations completed for engineerId={}", engineerId);
    }

    @Override
    public void approveOffboardingWaiver(Long engineerId, String reason, Long approvalRequestId, Long actorUserId) {
        if (engineerId == null) return;
        waiverCache.put(engineerId, new WaiverRecord(engineerId, reason, approvalRequestId, actorUserId, LocalDateTime.now()));
        log.warn("Offboarding waiver approved for engineerId={}, approvalRequestId={}, reason={}",
                engineerId, approvalRequestId, reason);
    }
}
