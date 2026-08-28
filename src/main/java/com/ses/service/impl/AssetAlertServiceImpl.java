package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.AssetAlertService;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAlertServiceImpl implements AssetAlertService {

    private final AssetAssignmentMapper assetAssignmentMapper;
    private final AssetMapper assetMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final SysUserMapper sysUserMapper;
    private final UserOrganizationMapper userOrganizationMapper;
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
            as.setStatus("OVERDUE");
            assetAssignmentMapper.updateById(as);

            Asset asset = assetMapper.selectById(as.getAssetId());
            String assetTag = asset != null ? asset.getAssetTag() : "ID#" + as.getAssetId();
            String dedupeKey = "asset:overdue:" + as.getId() + ":" + today;

            publishToRelevantUsers(
                    "ASSET_OVERDUE",
                    "【返却期限超過】資産「" + assetTag + "」が返却予定日を超過しています",
                    "貸与先: " + as.getAssigneeType() + "#" + as.getAssigneeId() + "、予定日: " + as.getExpectedReturnDate(),
                    "/asset/list",
                    dedupeKey,
                    recipientsForAssignment(as, true)
            );
            alertCount++;
        }

        // 7日前 / 3日前 / 当日 接近通知
        List<Integer> reminderDays = List.of(0, 3, 7);
        for (int days : reminderDays) {
            LocalDate targetDate = today.plusDays(days);
            List<AssetAssignment> upcomingList = assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
                    .eq(AssetAssignment::getStatus, "ACTIVE")
                    .eq(AssetAssignment::getExpectedReturnDate, targetDate));

            for (AssetAssignment assignment : upcomingList) {
                String dedupeKey = "asset:reminder:" + assignment.getId() + ":" + days + ":" + today;
                String title = days == 0 ? "【返却期日】本日が貸与資産の返却期日です" : String.format("【返却リマインド】資産返却期日まであと%d日です", days);
                String content = String.format("貸与資産ID#%d の返却期日は %s です。", assignment.getAssetId(), targetDate);
                publishToRelevantUsers(
                        "ASSET_RETURN_REMINDER",
                        title,
                        content,
                        "/asset/list",
                        dedupeKey,
                        recipientsForAssignment(assignment, true)
                );
            }
        }

        log.info("Overdue and upcoming asset assignments check completed: found={}", alertCount);
        return alertCount;
    }

    @Override
    @Transactional
    public int checkExpiringLeases() {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(30);

        List<Asset> expiringList = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .ne(Asset::getStatus, "DISPOSED")
                .isNotNull(Asset::getLeaseExpiry)
                .between(Asset::getLeaseExpiry, today, threshold));

        int alertCount = 0;
        for (Asset asset : expiringList) {
            String dedupeKey = "asset:lease-expiring:" + asset.getId() + ":" + asset.getLeaseExpiry();
            List<AssetAssignment> currentAssignments = assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
                    .eq(AssetAssignment::getAssetId, asset.getId())
                    .in(AssetAssignment::getStatus, List.of("ACTIVE", "OVERDUE")));
            Set<Long> recipients = new LinkedHashSet<>(managementUserIds());
            currentAssignments.forEach(assignment -> recipients.addAll(recipientsForAssignment(assignment, false)));
            publishToRelevantUsers(
                    "ASSET_LEASE_EXPIRING",
                    "【リース満了予告】資産「" + asset.getAssetTag() + "」のリース満了日が近づいています",
                    "資産名: " + asset.getAssetName() + "、リース満了日: " + asset.getLeaseExpiry() + " (30日以内)",
                    "/asset/list",
                    dedupeKey,
                    recipients
            );
            alertCount++;
        }
        log.info("Expiring leases check completed: found={}", alertCount);
        return alertCount;
    }

    @Override
    @Transactional
    public void notifyLostAssetIncident(Asset asset, String incidentDetails, Long reporterUserId) {
        if (asset == null) return;
        String dedupeKey = "asset:lost:" + asset.getId() + ":" + System.currentTimeMillis();
        Set<Long> recipients = new LinkedHashSet<>(managementUserIds());
        if (reporterUserId != null) {
            recipients.add(reporterUserId);
        }
        assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
                        .eq(AssetAssignment::getAssetId, asset.getId())
                        .in(AssetAssignment::getStatus, List.of("ACTIVE", "OVERDUE")))
                .forEach(assignment -> recipients.addAll(recipientsForAssignment(assignment, false)));
        publishToRelevantUsers(
                "ASSET_LOST_INCIDENT",
                "【緊急: 資産紛失インシデント】資産「" + asset.getAssetTag() + "」の紛失が報告されました",
                "報告者ID: " + reporterUserId + "、詳細: " + incidentDetails + "。直ちに外部アカウント停止およびリモートワイプ等の初動対応を実施してください。",
                "/asset/list",
                dedupeKey,
                recipients
        );
        log.warn("Lost asset incident notification published for assetTag={}", asset.getAssetTag());
    }

    @Override
    public List<AssetAssignment> getOverdueAssignments() {
        LocalDate today = LocalDate.now();
        return assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
                .and(w -> w.eq(AssetAssignment::getStatus, "OVERDUE")
                        .or(ow -> ow.eq(AssetAssignment::getStatus, "ACTIVE")
                .isNotNull(AssetAssignment::getExpectedReturnDate)
                .lt(AssetAssignment::getExpectedReturnDate, today))));
    }

    /** 対象者、対象者の現行上長、管理担当（管理者/HR）へ個別通知する。全体配信へフォールバックしない。 */
    private Set<Long> recipientsForAssignment(AssetAssignment assignment, boolean includeManagement) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (assignment == null || assignment.getAssigneeId() == null) {
            return recipients;
        }
        if ("USER".equals(assignment.getAssigneeType())) {
            recipients.add(assignment.getAssigneeId());
        } else if ("ENGINEER".equals(assignment.getAssigneeType())) {
            EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerId(assignment.getAssigneeId());
            if (link != null && link.getSysUserId() != null) {
                recipients.add(link.getSysUserId());
            }
        }
        Set<Long> initial = new LinkedHashSet<>(recipients);
        for (Long userId : initial) {
            userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                            .eq(UserOrganization::getUserId, userId)
                            .eq(UserOrganization::getPrimaryFlag, 1)
                            .le(UserOrganization::getValidFrom, LocalDate.now())
                            .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, LocalDate.now())))
                    .stream().map(UserOrganization::getManagerUserId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(recipients::add);
        }
        if (includeManagement) {
            recipients.addAll(managementUserIds());
        }
        return recipients;
    }

    private Set<Long> managementUserIds() {
        return new LinkedHashSet<>(sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .select(SysUser::getId)
                        .in(SysUser::getRole, List.of("管理者", "HR"))
                        .eq(SysUser::getStatus, 1))
                .stream().map(SysUser::getId).filter(java.util.Objects::nonNull).toList());
    }

    private void publishToRelevantUsers(String type, String title, String message, String linkUrl,
                                        String dedupeKey, Set<Long> recipientIds) {
        for (Long recipientId : recipientIds) {
            SysUser recipient = sysUserMapper.selectById(recipientId);
            if (recipient == null || !Integer.valueOf(1).equals(recipient.getStatus())) {
                continue;
            }
            String menuKey = "要員".equals(recipient.getRole()) ? "my-assets" : "asset-management";
            notificationService.publishToUser(recipientId, type, title, message, linkUrl, dedupeKey, menuKey);
        }
    }
}
