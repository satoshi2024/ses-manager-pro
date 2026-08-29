package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.asset.OffboardingClearanceResultDto;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.LicenseAssignment;
import com.ses.entity.AssetOffboardingWaiver;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.AssetOffboardingWaiverMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LicenseAssignmentMapper;
import com.ses.service.AssetAssignmentService;
import com.ses.service.AssetOffboardingService;
import com.ses.service.ExternalAccountService;
import com.ses.service.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetOffboardingServiceImpl implements AssetOffboardingService {

    private final AssetAssignmentMapper assetAssignmentMapper;
    private final ExternalAccountReferenceMapper externalAccountReferenceMapper;
    private final LicenseAssignmentMapper licenseAssignmentMapper;
    private final AssetMapper assetMapper;
    private final AssetOffboardingWaiverMapper assetOffboardingWaiverMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final LifecycleTaskMapper lifecycleTaskMapper;
    private final LifecycleCaseMapper lifecycleCaseMapper;
    private final AssetAssignmentService assetAssignmentService;
    private final ExternalAccountService externalAccountService;
    private final LicenseService licenseService;

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
            blockingItems.add("未失効外部アカウント: " + maskIdentifier(acc.getAccountIdentifier()) + " (System#" + acc.getSystemId() + ")");
        }
        for (LicenseAssignment lic : activeLicenses) {
            blockingItems.add("未解放有償ライセンス: Plan#" + lic.getPlanId() + " [割当ID: " + lic.getId() + "]");
        }

        boolean hasWaiver = assetOffboardingWaiverMapper.selectValidByEngineerId(engineerId) != null;
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
    /** DB状態を先に確定し、provider I/OはDBトランザクション外で実行する。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    public void triggerOffboardingRevocations(Long engineerId, Long actorUserId) {
        log.info("Triggering offboarding revocations for engineerId={}, actorUserId={}", engineerId, actorUserId);

        // 1. 外部アカウントの失効要求
        List<ExternalAccountReference> activeAccounts = externalAccountReferenceMapper.selectActiveByAssignee("ENGINEER", engineerId);
        for (ExternalAccountReference acc : activeAccounts) {
            // 要求送信・確認・タイムアウト再試行の契約を共通サービスへ委譲する。
            externalAccountService.requestRevokeWithIdempotency(acc.getId(), acc.getIdempotencyKey(), actorUserId);
        }

        // 2. 有償ライセンスの一括解放
        List<LicenseAssignment> activeLicenses = licenseAssignmentMapper.selectActiveByAssignee("ENGINEER", engineerId);
        for (LicenseAssignment lic : activeLicenses) {
            licenseService.releaseLicense(lic.getId(), LocalDate.now(), actorUserId);
        }

        log.info("Offboarding revocations completed for engineerId={}", engineerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveOffboardingWaiver(Long engineerId, String reason, Long approvalRequestId, Long actorUserId) {
        if (engineerId == null || approvalRequestId == null || !StringUtils.hasText(reason)) {
            throw new BusinessException(400, "退社例外免除には対象要員、理由、承認申請IDが必要です。");
        }

        ApprovalRequest approval = approvalRequestMapper.selectByIdForUpdate(approvalRequestId);
        if (approval == null || !"LIFECYCLE_EXCEPTION".equals(approval.getRequestType())
                || !isApproved(approval.getStatus()) || !approvalTargetMatches(approval, engineerId)) {
            throw new BusinessException(400, "有効な承認済みLIFECYCLE_EXCEPTION申請が対象要員に紐付いていません。");
        }

        // ApprovalEngine再送時は同じ承認を一度だけ台帳へ追記する。
        if (assetOffboardingWaiverMapper.selectByApprovalRequestId(approvalRequestId) == null) {
            assetOffboardingWaiverMapper.insert(AssetOffboardingWaiver.builder()
                    .engineerId(engineerId)
                    .approvalRequestId(approvalRequestId)
                    .reason(reason.trim())
                    .approvedBy(actorUserId)
                    .approvedAt(LocalDateTime.now())
                    .build());
        }
        log.warn("Offboarding waiver recorded: engineerId={}, approvalRequestId={}", engineerId, approvalRequestId);
    }

    private boolean approvalTargetMatches(ApprovalRequest approval, Long engineerId) {
        if ("ENGINEER".equals(approval.getTargetType())) {
            return engineerId.equals(approval.getTargetId());
        }
        if (!"LIFECYCLE_TASK".equals(approval.getTargetType()) || approval.getTargetId() == null) {
            return false;
        }
        var task = lifecycleTaskMapper.selectById(approval.getTargetId());
        if (task == null || !"RESIGN_ASSET_RETURN".equals(task.getTaskCode())) {
            return false;
        }
        var lcCase = lifecycleCaseMapper.selectById(task.getCaseId());
        return lcCase != null && engineerId.equals(lcCase.getEngineerId());
    }

    private boolean isApproved(String status) {
        return status != null && "APPROVED".equalsIgnoreCase(status);
    }

    private static String maskIdentifier(String identifier) {
        if (!StringUtils.hasText(identifier)) return "***";
        int atIndex = identifier.indexOf('@');
        if (atIndex > 2) return identifier.substring(0, 2) + "***" + identifier.substring(atIndex);
        if (identifier.length() > 4) return identifier.substring(0, 2) + "***" + identifier.substring(identifier.length() - 2);
        return "***";
    }
}
