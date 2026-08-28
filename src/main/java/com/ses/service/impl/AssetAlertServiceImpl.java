package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.service.AssetAlertService;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAlertServiceImpl implements AssetAlertService {

    private final AssetAssignmentMapper assetAssignmentMapper;
    private final AssetMapper assetMapper;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public int checkOverdueAssignments() {
        LocalDate today = LocalDate.now();
        List<AssetAssignment> activeList = assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
                .eq(AssetAssignment::getStatus, "ACTIVE")
                .isNotNull(AssetAssignment::getExpectedReturnDate)
                .lt(AssetAssignment::getExpectedReturnDate, today));

        int alertCount = 0;
        for (AssetAssignment as : activeList) {
            Asset asset = assetMapper.selectById(as.getAssetId());
            String assetTag = asset != null ? asset.getAssetTag() : "ID#" + as.getAssetId();
            String dedupeKey = "asset:overdue:" + as.getId() + ":" + today;

            notificationService.publish(
                    "ASSET_OVERDUE",
                    "【返却期限超過】資産「" + assetTag + "」が返却予定日を超過しています",
                    "貸与先: " + as.getAssigneeType() + "#" + as.getAssigneeId() + "、予定日: " + as.getExpectedReturnDate(),
                    "/asset/list",
                    dedupeKey,
                    "asset-management"
            );
            alertCount++;
        }

        log.info("Overdue asset assignments check completed: found={}", alertCount);
        return alertCount;
    }

    @Override
    @Transactional
    public int checkExpiringLeases() {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(30);

        List<Asset> expiringAssets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .ne(Asset::getStatus, "DISPOSED")
                .isNotNull(Asset::getLeaseExpiry)
                .between(Asset::getLeaseExpiry, today, threshold));

        int alertCount = 0;
        for (Asset a : expiringAssets) {
            String dedupeKey = "asset:lease-expiry:" + a.getId() + ":" + today.getYear() + "-" + today.getMonthValue();
            notificationService.publish(
                    "ASSET_LEASE_EXPIRING",
                    "【リース満了接近】資産「" + a.getAssetTag() + "」のリース期間が30日以内に満了します",
                    "資産名: " + a.getAssetName() + "、満了日: " + a.getLeaseExpiry(),
                    "/asset/list",
                    dedupeKey,
                    "asset-management"
            );
            alertCount++;
        }

        log.info("Expiring leases check completed: found={}", alertCount);
        return alertCount;
    }

    @Override
    public void notifyLostAssetIncident(Asset asset, String incidentDetails, Long reporterUserId) {
        String dedupeKey = "asset:lost:" + asset.getId() + ":" + System.currentTimeMillis();
        notificationService.publish(
                "ASSET_LOST_INCIDENT",
                "【緊急: 資産紛失インシデント】資産「" + asset.getAssetTag() + " (" + asset.getAssetName() + ")」の紛失が報告されました",
                "報告者ID: " + reporterUserId + "、詳細: " + incidentDetails,
                "/asset/list",
                dedupeKey,
                "asset-management"
        );
        log.warn("Lost asset incident notification published for assetTag={}", asset.getAssetTag());
    }

    @Override
    public List<AssetAssignment> getOverdueAssignments() {
        LocalDate today = LocalDate.now();
        return assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
                .eq(AssetAssignment::getStatus, "ACTIVE")
                .isNotNull(AssetAssignment::getExpectedReturnDate)
                .lt(AssetAssignment::getExpectedReturnDate, today)
                .orderByAsc(AssetAssignment::getExpectedReturnDate));
    }
}
