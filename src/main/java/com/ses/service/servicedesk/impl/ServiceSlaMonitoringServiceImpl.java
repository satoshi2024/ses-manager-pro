package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * SLA監視・アラート通知サービス実装
 * - SLA超過判定・クロック状態更新
 * - 4段階エスカレーション通知（担当者 -> 契約営業 -> 顧客主担当営業 -> 管理者全員）
 * - ハードコードID 1へのフォールバック完全排除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceSlaMonitoringServiceImpl implements ServiceSlaMonitoringService {

    private final ServiceSlaClockMapper slaClockMapper;
    private final ServiceRequestMapper serviceRequestMapper;
    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkAndNotifyBreaches() {
        checkSlaBreaches(LocalDateTime.now(clock));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int checkSlaBreaches(LocalDateTime asOf) {
        LocalDateTime now = asOf != null ? asOf : LocalDateTime.now(clock);
        int breachedCount = 0;

        List<ServiceSlaClock> runningClocks = slaClockMapper.selectList(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getStatus, "RUNNING")
        );

        for (ServiceSlaClock clk : runningClocks) {
            ServiceRequest req = serviceRequestMapper.selectById(clk.getServiceRequestId());
            if (req == null || "RESOLVED".equals(req.getStatus()) || "CLOSED".equals(req.getStatus())) {
                continue;
            }

            boolean clockUpdated = false;

            // 1. 初回応答期限超過チェック
            if (Boolean.FALSE.equals(clk.getResponseBreached()) && clk.getFirstRespondedAt() == null
                    && clk.getResponseDeadline() != null && clk.getResponseDeadline().isBefore(now)) {
                clk.setResponseBreached(true);
                clockUpdated = true;
                breachedCount++;

                notifySlaBreach(req, clk, "RESPONSE", "SLA初回応答期限超過",
                        String.format("リクエスト %s [%s] の初回応答期限を超過しました", req.getRequestNo(), req.getSubject()));
            }

            // 2. 解決目標期限超過チェック
            if (Boolean.FALSE.equals(clk.getResolveBreached()) && clk.getResolvedAt() == null
                    && clk.getResolveDeadline() != null && clk.getResolveDeadline().isBefore(now)) {
                clk.setResolveBreached(true);
                clockUpdated = true;
                breachedCount++;

                notifySlaBreach(req, clk, "RESOLVE", "SLA解決目標期限超過",
                        String.format("リクエスト %s [%s] の解決目標期限を超過しました", req.getRequestNo(), req.getSubject()));
            }

            if (clockUpdated) {
                clk.setUpdatedAt(now);
                slaClockMapper.updateById(clk);
            }
        }

        return breachedCount;
    }

    private void notifySlaBreach(ServiceRequest req, ServiceSlaClock clk, String breachType, String title, String message) {
        List<Long> recipients = resolveNotificationRecipients(req);
        if (recipients.isEmpty()) {
            log.warn("SLAアラート通知先が存在しません: requestId={}, breachType={}", req.getId(), breachType);
            return;
        }

        String dedupeKey = String.format("SLA_BREACH:%d:%d:%s", req.getId(), clk.getRoundNo(), breachType);
        String linkUrl = NotificationLinks.serviceDeskDetail(req.getId());

        for (Long recipientId : recipients) {
            try {
                notificationService.publishToUser(
                        recipientId,
                        "SLA_BREACH",
                        title,
                        message,
                        linkUrl,
                        dedupeKey,
                        "service-desk"
                );
            } catch (Exception e) {
                log.error("SLAアラート通知送信失敗: recipientId={}, dedupeKey={}", recipientId, dedupeKey, e);
            }
        }
    }

    /**
     * 通知受信者の4段階エスカレーション解決
     * 1. リクエスト担当者 (owner_user_id)
     * 2. 関連契約の担当営業 (contract.sales_user_id)
     * 3. 顧客の有効契約担当営業 (customer primary sales rep)
     * 4. 有効な全管理者 (active admins)
     */
    public List<Long> resolveNotificationRecipients(ServiceRequest req) {
        if (req == null) {
            return Collections.emptyList();
        }

        // ① リクエスト担当者
        if (req.getOwnerUserId() != null) {
            SysUser owner = sysUserMapper.selectById(req.getOwnerUserId());
            if (owner != null && Integer.valueOf(1).equals(owner.getStatus()) && Integer.valueOf(0).equals(owner.getDeletedFlag())) {
                return List.of(owner.getId());
            }
        }

        // ② 関連契約の担当営業
        if (req.getContractId() != null) {
            Contract contract = contractMapper.selectById(req.getContractId());
            if (contract != null && contract.getSalesUserId() != null) {
                SysUser salesUser = sysUserMapper.selectById(contract.getSalesUserId());
                if (salesUser != null && Integer.valueOf(1).equals(salesUser.getStatus()) && Integer.valueOf(0).equals(salesUser.getDeletedFlag())) {
                    return List.of(salesUser.getId());
                }
            }
        }

        // ③ 顧客の有効契約担当営業
        if (req.getCustomerId() != null) {
            List<Contract> contracts = contractMapper.selectList(
                    new LambdaQueryWrapper<Contract>()
                            .eq(Contract::getCustomerId, req.getCustomerId())
                            .eq(Contract::getStatus, "稼動中")
                            .isNotNull(Contract::getSalesUserId)
                            .orderByDesc(Contract::getId)
            );
            for (Contract c : contracts) {
                SysUser salesUser = sysUserMapper.selectById(c.getSalesUserId());
                if (salesUser != null && Integer.valueOf(1).equals(salesUser.getStatus()) && Integer.valueOf(0).equals(salesUser.getDeletedFlag())) {
                    return List.of(salesUser.getId());
                }
            }
        }

        // ④ 有効な全管理者へのエスカレーション
        List<SysUser> activeAdmins = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, "管理者")
                        .eq(SysUser::getStatus, 1)
                        .eq(SysUser::getDeletedFlag, 0)
        );
        if (!activeAdmins.isEmpty()) {
            return activeAdmins.stream().map(SysUser::getId).toList();
        }

        return Collections.emptyList();
    }
}
