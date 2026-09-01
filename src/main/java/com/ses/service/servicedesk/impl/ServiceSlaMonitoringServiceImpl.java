package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.entity.ServiceSlaEscalation;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.ServiceSlaEscalationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Duration;
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
    private final ServiceSlaEscalationMapper escalationMapper;
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
            boolean responseWasBreached = Boolean.TRUE.equals(clk.getResponseBreached());
            boolean resolveWasBreached = Boolean.TRUE.equals(clk.getResolveBreached());
            List<SlaNotice> notices = new java.util.ArrayList<>();
            List<ServiceSlaEscalation> retryRows = escalationMapper.selectList(
                    new LambdaQueryWrapper<ServiceSlaEscalation>()
                            .eq(ServiceSlaEscalation::getSlaClockId, clk.getId())
                            .eq(ServiceSlaEscalation::getStatus, "RETRY"));
            boolean responseFirstRetryPending = hasRetry(retryRows, "RESPONSE", "FIRST");
            boolean resolveFirstRetryPending = hasRetry(retryRows, "RESOLVE", "FIRST");

            // 1. 初回応答期限超過チェック
            if (!responseWasBreached && clk.getFirstRespondedAt() == null
                    && clk.getResponseDeadline() != null && !clk.getResponseDeadline().isAfter(now)) {
                clk.setResponseBreached(true);
                clockUpdated = true;
                breachedCount++;
                notices.add(new SlaNotice("RESPONSE", "FIRST", "SLA初回応答期限超過",
                        String.format("リクエスト %s [%s] の初回応答期限を超過しました", req.getRequestNo(), req.getSubject())));
            } else if (responseWasBreached && !responseFirstRetryPending
                    && shouldContinue(clk.getLastResponseAlertAt(), now)) {
                notices.add(new SlaNotice("RESPONSE", "CONTINUING", "SLA初回応答超過継続",
                        String.format("リクエスト %s [%s] の初回応答期限超過が継続しています", req.getRequestNo(), req.getSubject())));
                clockUpdated = true;
            }

            // 2. 解決目標期限超過チェック
            if (!resolveWasBreached && clk.getResolvedAt() == null
                    && clk.getResolveDeadline() != null && !clk.getResolveDeadline().isAfter(now)) {
                clk.setResolveBreached(true);
                clockUpdated = true;
                breachedCount++;
                notices.add(new SlaNotice("RESOLVE", "FIRST", "SLA解決目標期限超過",
                        String.format("リクエスト %s [%s] の解決目標期限を超過しました", req.getRequestNo(), req.getSubject())));
            } else if (resolveWasBreached && !resolveFirstRetryPending
                    && shouldContinue(clk.getLastResolveAlertAt(), now)) {
                notices.add(new SlaNotice("RESOLVE", "CONTINUING", "SLA解決目標超過継続",
                        String.format("リクエスト %s [%s] の解決目標期限超過が継続しています", req.getRequestNo(), req.getSubject())));
                clockUpdated = true;
            }

            // 期限30分前からwarningを一度だけ発行する。
            if (!responseWasBreached && !Boolean.TRUE.equals(clk.getResponseWarningSent())
                    && dueForWarning(clk.getResponseDeadline(), now)) {
                clk.setResponseWarningSent(true);
                clockUpdated = true;
                notices.add(new SlaNotice("RESPONSE", "WARNING", "SLA初回応答期限warning",
                        String.format("リクエスト %s [%s] の初回応答期限まで30分以内です", req.getRequestNo(), req.getSubject())));
            }
            if (!resolveWasBreached && !Boolean.TRUE.equals(clk.getResolveWarningSent())
                    && dueForWarning(clk.getResolveDeadline(), now)) {
                clk.setResolveWarningSent(true);
                clockUpdated = true;
                notices.add(new SlaNotice("RESOLVE", "WARNING", "SLA解決期限warning",
                        String.format("リクエスト %s [%s] の解決期限まで30分以内です", req.getRequestNo(), req.getSubject())));
            }

            // 無接收者・部分失敗の通知は台帳へ残し、退避時間経過後に同じdedupe keyで再送する。
            for (ServiceSlaEscalation retry : retryRows) {
                if (retry.getNextRetryAt() != null && retry.getNextRetryAt().isAfter(now)) {
                    continue;
                }
                if (notices.stream().noneMatch(n -> n.breachType().equals(retry.getBreachType())
                        && n.stage().equals(retry.getStage()))) {
                    notices.add(retryNotice(req, retry));
                }
            }

            if (clockUpdated) {
                clk.setUpdatedAt(now);
                int version = clk.getVersion() == null ? 0 : clk.getVersion();
                int affected = slaClockMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getId, clk.getId())
                        .eq(ServiceSlaClock::getVersion, version)
                        .set(ServiceSlaClock::getResponseBreached, clk.getResponseBreached())
                        .set(ServiceSlaClock::getResponseWarningSent, clk.getResponseWarningSent())
                        .set(ServiceSlaClock::getResolveBreached, clk.getResolveBreached())
                        .set(ServiceSlaClock::getResolveWarningSent, clk.getResolveWarningSent())
                        .set(ServiceSlaClock::getLastResponseAlertAt, clk.getLastResponseAlertAt())
                        .set(ServiceSlaClock::getLastResolveAlertAt, clk.getLastResolveAlertAt())
                        .set(ServiceSlaClock::getUpdatedAt, clk.getUpdatedAt())
                        .set(ServiceSlaClock::getVersion, version + 1));
                if (affected != 1) {
                    // stale workerは通知も状態イベントも発行しない。
                    continue;
                }
                clk.setVersion(version + 1);
            }

            boolean responseAlertSent = false;
            boolean resolveAlertSent = false;
            for (SlaNotice notice : notices) {
                boolean sent = notifySlaEvent(req, clk, notice, now);
                // WARNING はbreach継続間隔の起点にしない。
                if (sent && !"WARNING".equals(notice.stage()) && "RESPONSE".equals(notice.breachType())) {
                    responseAlertSent = true;
                }
                if (sent && !"WARNING".equals(notice.stage()) && "RESOLVE".equals(notice.breachType())) {
                    resolveAlertSent = true;
                }
            }
            if (responseAlertSent || resolveAlertSent) {
                int version = clk.getVersion() == null ? 0 : clk.getVersion();
                var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getId, clk.getId())
                        .eq(ServiceSlaClock::getVersion, version)
                        .set(ServiceSlaClock::getVersion, version + 1)
                        .set(ServiceSlaClock::getUpdatedAt, now);
                if (responseAlertSent) update.set(ServiceSlaClock::getLastResponseAlertAt, now);
                if (resolveAlertSent) update.set(ServiceSlaClock::getLastResolveAlertAt, now);
                if (slaClockMapper.update(null, update) == 1) {
                    clk.setVersion(version + 1);
                    if (responseAlertSent) clk.setLastResponseAlertAt(now);
                    if (resolveAlertSent) clk.setLastResolveAlertAt(now);
                }
            }
        }

        return breachedCount;
    }

    private boolean notifySlaEvent(ServiceRequest req, ServiceSlaClock clk, SlaNotice notice, LocalDateTime now) {
        List<Long> recipients = resolveNotificationRecipients(req);
        String bucket = "CONTINUING".equals(notice.stage())
                ? String.valueOf(now.toLocalDate().toString() + "-" + (now.getHour() * 60 + now.getMinute()) / 30)
                : notice.stage();
        String dedupeKey = String.format("SLA_%s:%d:%d:%s:%s", notice.stage(), req.getId(),
                clk.getRoundNo(), notice.breachType(), bucket);
        if (recipients.isEmpty()) {
            persistEscalation(req, clk, notice, dedupeKey, 0, "受信者が存在しません", now);
            return false;
        }
        String linkUrl = NotificationLinks.serviceDeskDetail(req.getId());
        boolean sent = false;
        boolean allRecipientsSent = true;
        String error = null;

        for (Long recipientId : recipients) {
            try {
                notificationService.publishToUser(
                        recipientId,
                        "WARNING".equals(notice.stage()) ? "SLA_WARNING"
                                : ("CONTINUING".equals(notice.stage()) ? "SLA_BREACH_CONTINUING" : "SLA_BREACH"),
                        notice.title(),
                        notice.message(),
                        linkUrl,
                        dedupeKey,
                        "service-desk"
                );
                sent = true;
            } catch (Exception e) {
                allRecipientsSent = false;
                error = e.getMessage() != null && !e.getMessage().isBlank()
                        ? e.getMessage() : "SLAアラート通知送信失敗";
                log.error("SLAアラート通知送信失敗: recipientId={}, dedupeKey={}", recipientId, dedupeKey, e);
            }
        }
        persistEscalation(req, clk, notice, dedupeKey, recipients.size(), error, now);
        return sent && allRecipientsSent;
    }

    private boolean dueForWarning(LocalDateTime deadline, LocalDateTime now) {
        return deadline != null && deadline.isAfter(now) && !deadline.minusMinutes(30).isAfter(now);
    }

    private boolean shouldContinue(LocalDateTime lastAlertAt, LocalDateTime now) {
        return lastAlertAt == null || Duration.between(lastAlertAt, now).toMinutes() >= 30;
    }

    private boolean hasRetry(List<ServiceSlaEscalation> rows, String breachType, String stage) {
        return rows.stream().anyMatch(row -> breachType.equals(row.getBreachType())
                && stage.equals(row.getStage()) && "RETRY".equals(row.getStatus()));
    }

    private SlaNotice retryNotice(ServiceRequest req, ServiceSlaEscalation retry) {
        String label = "RESPONSE".equals(retry.getBreachType()) ? "初回応答" : "解決目標";
        String title = switch (retry.getStage()) {
            case "WARNING" -> "SLA" + label + "期限warning再送";
            case "CONTINUING" -> "SLA" + label + "超過継続再送";
            default -> "SLA" + label + "期限超過再送";
        };
        String message = switch (retry.getStage()) {
            case "WARNING" -> String.format("リクエスト %s [%s] のSLA warning通知を再送します",
                    req.getRequestNo(), req.getSubject());
            case "CONTINUING" -> String.format("リクエスト %s [%s] のSLA超過継続通知を再送します",
                    req.getRequestNo(), req.getSubject());
            default -> String.format("リクエスト %s [%s] のSLA期限超過通知を再送します",
                    req.getRequestNo(), req.getSubject());
        };
        return new SlaNotice(retry.getBreachType(), retry.getStage(), title, message);
    }

    private void persistEscalation(ServiceRequest req, ServiceSlaClock clk, SlaNotice notice,
                                   String dedupeKey, int recipientCount, String error, LocalDateTime now) {
        ServiceSlaEscalation row = escalationMapper.selectOne(new LambdaQueryWrapper<ServiceSlaEscalation>()
                .eq(ServiceSlaEscalation::getDedupeKey, dedupeKey));
        if (row == null) {
            row = ServiceSlaEscalation.builder()
                    .serviceRequestId(req.getId()).slaClockId(clk.getId()).roundNo(clk.getRoundNo())
                    .breachType(notice.breachType()).stage(notice.stage()).dedupeKey(dedupeKey)
                    .recipientCount(recipientCount).status(error == null ? "SENT" : "RETRY")
                    .attemptCount(1).lastError(error).lastAttemptAt(now)
                    .nextRetryAt(error == null ? null : now.plusMinutes(5))
                    .createdAt(now).updatedAt(now).build();
            try {
                escalationMapper.insert(row);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // 別workerが同じdedupe keyを確定済み。次回実行でその台帳を再利用する。
            }
            return;
        }
        row.setRecipientCount(Math.max(row.getRecipientCount() == null ? 0 : row.getRecipientCount(), recipientCount));
        row.setStatus(error == null ? "SENT" : "RETRY");
        row.setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1);
        row.setLastError(error);
        row.setLastAttemptAt(now);
        row.setNextRetryAt(error == null ? null : now.plusMinutes(5));
        row.setUpdatedAt(now);
        escalationMapper.updateById(row);
    }

    private record SlaNotice(String breachType, String stage, String title, String message) { }

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
