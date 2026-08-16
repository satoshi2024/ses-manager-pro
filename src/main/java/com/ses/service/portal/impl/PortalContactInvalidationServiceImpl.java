package com.ses.service.portal.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.service.portal.PortalContactInvalidationService;
import com.ses.service.portal.PortalSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * R1.5の実装。SQL JOINで失効担当者とemail一致するportal userを特定し、停止＋全session失効する。
 * 停止対象は同種組織（顧客担当者→CUSTOMER org、BP担当者→BP org）のみ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalContactInvalidationServiceImpl implements PortalContactInvalidationService {

    private final JdbcTemplate jdbcTemplate;
    private final PortalUserMapper userMapper;
    private final PortalOrganizationMapper organizationMapper;
    private final PortalSessionService sessionService;
    private final Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int invalidateByContacts() {
        LocalDate today = LocalDate.now(clock);
        int total = 0;

        // 1) 顧客担当者の退職/無効化（status=退職 / valid_to到来 / 論理削除）。
        // S13-R1-P2-08: email一致に加えて cc.customer_id = o.customer_id を検査し、
        // 他顧客の同名email担当者の退職で自組織userを誤停止しない。
        List<Long> customerUserIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT u.id FROM t_portal_user u "
                        + "JOIN m_portal_organization o ON o.id = u.portal_org_id AND o.type = 'CUSTOMER' "
                        + "JOIN t_customer_contact cc ON cc.email = u.email AND cc.customer_id = o.customer_id "
                        + "WHERE u.status = 'ACTIVE' AND u.deleted_flag = 0 "
                        + "AND (cc.deleted_flag = 1 OR cc.status = '退職' "
                        + "     OR (cc.valid_to IS NOT NULL AND cc.valid_to < ?))",
                Long.class, today);
        for (Long userId : customerUserIds) {
            suspendAndRevoke(userId, "CONTACT_INVALID");
            total++;
        }

        // 2) BP担当者の論理削除（同様に bp_company_id 一致を検査）
        List<Long> bpUserIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT u.id FROM t_portal_user u "
                        + "JOIN m_portal_organization o ON o.id = u.portal_org_id AND o.type = 'BP' "
                        + "JOIN t_bp_contact bc ON bc.email = u.email AND bc.bp_company_id = o.bp_company_id "
                        + "WHERE u.status = 'ACTIVE' AND u.deleted_flag = 0 AND bc.deleted_flag = 1",
                Long.class);
        for (Long userId : bpUserIds) {
            suspendAndRevoke(userId, "CONTACT_INVALID");
            total++;
        }

        if (total > 0) {
            log.info("portal担当者失効連動: {} userを停止しました", total);
        }
        return total;
    }

    private void suspendAndRevoke(Long userId, String reason) {
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", userId)
                .set("status", "SUSPENDED"));
        sessionService.revokeAllForUser(userId, reason);
    }
}
