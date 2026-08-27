package com.ses.service.portal.impl;

import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.service.portal.PortalMailService;
import com.ses.service.portal.PortalNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * portal通知の実装。同一組織×種別×日の重複送信はインメモリで抑止する（単一インスタンス運用の前提。
 * 複数インスタンス展開時は共有storeへ置き換えること）。通知送信失敗は業務を妨げない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalNotificationServiceImpl implements PortalNotificationService {

    private final PortalOrganizationMapper organizationMapper;
    private final PortalUserMapper userMapper;
    private final PortalMailService mailService;
    private final MessageSource messageSource;

    /** dedupeキー: type:orgId:yyyyMMdd。過去日付のキーは放置（上限は組織数×種別で有界） */
    private final Set<String> sentToday = ConcurrentHashMap.newKeySet();

    @Override
    public void notifyCustomerOrganization(Long customerId, String type, String subjectKey, String bodyKey,
                                           Object[] args, String relativeLink) {
        PortalOrganization org = organizationMapper.selectByCustomerId(customerId);
        if (org == null) {
            return;
        }
        notifyOrg(org, type, subjectKey, bodyKey, args, relativeLink);
    }

    @Override
    public void notifyBpOrganization(Long bpCompanyId, String type, String subjectKey, String bodyKey,
                                     Object[] args, String relativeLink) {
        PortalOrganization org = organizationMapper.selectByBpCompanyId(bpCompanyId);
        if (org == null) {
            return;
        }
        notifyOrg(org, type, subjectKey, bodyKey, args, relativeLink);
    }

    private void notifyOrg(PortalOrganization org, String type, String subjectKey, String bodyKey,
                           Object[] args, String relativeLink) {
        String dedupeKey = type + ":" + org.getId() + ":" + LocalDate.now();
        if (!sentToday.add(dedupeKey)) {
            log.info("portal通知の重複を抑止しました: {}", dedupeKey);
            return;
        }
        List<PortalUser> users = userMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PortalUser>()
                        .eq(PortalUser::getPortalOrgId, org.getId())
                        .eq(PortalUser::getStatus, "ACTIVE")
                        .ne(PortalUser::getNotifyEmail, 0));
        String subject = messageSource.getMessage(subjectKey, args, Locale.JAPANESE);
        String body = messageSource.getMessage(bodyKey, args, Locale.JAPANESE);
        for (PortalUser user : users) {
            try {
                mailService.sendNotification(user.getEmail(), subject, body, relativeLink);
            } catch (RuntimeException e) {
                log.warn("portal通知メール送信に失敗しました: to={} type={} errorType={}",
                        com.ses.common.util.LogRedaction.maskEmail(user.getEmail()), type,
                        com.ses.common.util.LogRedaction.exceptionType(e));
            }
        }
    }
}
