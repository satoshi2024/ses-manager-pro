package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.service.NotificationService;
import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceSlaMonitoringServiceImpl implements ServiceSlaMonitoringService {

    private final ServiceRequestMapper serviceRequestMapper;
    private final ServiceSlaClockMapper slaClockMapper;
    private final NotificationService notificationService;
    private final java.time.Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int checkSlaBreaches(LocalDateTime asOf) {
        if (asOf == null) {
            asOf = LocalDateTime.now(clock);
        }

        // 監視対象: ステータスが RECEIVED または IN_PROGRESS のリクエスト
        List<ServiceRequest> activeRequests = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .in(ServiceRequest::getStatus, List.of("RECEIVED", "IN_PROGRESS"))
        );

        int processedCount = 0;

        for (ServiceRequest req : activeRequests) {
            int round = req.getReopenCount() == null ? 1 : (req.getReopenCount() + 1);

            ServiceSlaClock clock = slaClockMapper.selectOne(
                    new LambdaQueryWrapper<ServiceSlaClock>()
                            .eq(ServiceSlaClock::getServiceRequestId, req.getId())
                            .eq(ServiceSlaClock::getRoundNo, round)
                            .eq(ServiceSlaClock::getStatus, "RUNNING")
            );

            if (clock == null) {
                continue;
            }

            boolean clockUpdated = false;
            Long targetUserId = req.getOwnerUserId() != null ? req.getOwnerUserId() : 1L; // 担当者またはデフォルト管理者

            // 1. 初回応答期限超過チェック
            if (clock.getFirstRespondedAt() == null && clock.getResponseDeadline() != null) {
                if (asOf.isAfter(clock.getResponseDeadline())) {
                    if (Boolean.FALSE.equals(clock.getResponseBreached())) {
                        clock.setResponseBreached(true);
                        clockUpdated = true;
                    }
                    // Dedupe 通知送信
                    String dedupeKey = "SLA_RESPONSE_BREACH:" + req.getId() + ":" + round;
                    String title = "[SLA超過] 初回応答期限超過: " + req.getRequestNo();
                    String message = String.format("リクエスト「%s」の初回応答期限（%s）を超過しました。",
                            req.getSubject(), clock.getResponseDeadline().toString().replace('T', ' '));
                    String linkUrl = com.ses.common.constant.NotificationLinks.serviceDeskDetail(req.getId());

                    notificationService.publishToUser(targetUserId, "SERVICE_DESK_SLA_BREACH", title, message, linkUrl, dedupeKey, "serviceDesk");
                }
            }

            // 2. 解決期限超過チェック
            if (clock.getResolvedAt() == null && clock.getResolveDeadline() != null) {
                if (asOf.isAfter(clock.getResolveDeadline())) {
                    if (Boolean.FALSE.equals(clock.getResolveBreached())) {
                        clock.setResolveBreached(true);
                        clockUpdated = true;
                    }
                    // Dedupe 通知送信
                    String dedupeKey = "SLA_RESOLVE_BREACH:" + req.getId() + ":" + round;
                    String title = "[SLA超過] 解決期限超過: " + req.getRequestNo();
                    String message = String.format("リクエスト「%s」の解決期限（%s）を超過しました。",
                            req.getSubject(), clock.getResolveDeadline().toString().replace('T', ' '));
                    String linkUrl = com.ses.common.constant.NotificationLinks.serviceDeskDetail(req.getId());

                    notificationService.publishToUser(targetUserId, "SERVICE_DESK_SLA_BREACH", title, message, linkUrl, dedupeKey, "serviceDesk");
                } else if (asOf.plusHours(1).isAfter(clock.getResolveDeadline())) {
                    // 3. 解決前警告（残り1時間以内）
                    String dedupeKey = "SLA_RESOLVE_WARNING:" + req.getId() + ":" + round;
                    String title = "[SLA警告] 解決期限が迫っています: " + req.getRequestNo();
                    String message = String.format("リクエスト「%s」の解決期限（%s）まで1時間を切りました。",
                            req.getSubject(), clock.getResolveDeadline().toString().replace('T', ' '));
                    String linkUrl = com.ses.common.constant.NotificationLinks.serviceDeskDetail(req.getId());

                    notificationService.publishToUser(targetUserId, "SERVICE_DESK_SLA_WARNING", title, message, linkUrl, dedupeKey, "serviceDesk");
                }
            }

            if (clockUpdated) {
                clock.setUpdatedAt(asOf);
                slaClockMapper.updateById(clock);
                processedCount++;
            }
        }

        return processedCount;
    }
}
