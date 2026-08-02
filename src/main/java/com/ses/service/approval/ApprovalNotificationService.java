package com.ses.service.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.NotificationLinks;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 承認設定不足など、申請transactionがrollbackしても残す通知を扱う。 */
@Service
@RequiredArgsConstructor
public class ApprovalNotificationService {

    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;

    /**
     * route未設定通知は呼出元の申請transactionから分離する。
     * 呼出元が設定不足例外でrollbackしても管理者通知はcommitされる。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void notifyConfigGap(ApprovalRequestCommand command) {
        List<Long> adminIds = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, "管理者")
                        .eq(SysUser::getStatus, 1))
                .stream().map(SysUser::getId).toList();
        String dedupeKey = "approval-config-gap-" + command.requestType() + "-" + command.organizationId();
        for (Long adminId : adminIds) {
            notificationService.publishToUser(adminId, "APPROVAL_CONFIG_GAP",
                    "承認route設定不足",
                    "対象種別「" + command.requestType() + "」の承認routeまたは承認者が解決できませんでした。設定を確認してください。",
                    NotificationLinks.DASHBOARD, dedupeKey, "approval");
        }
    }
}
