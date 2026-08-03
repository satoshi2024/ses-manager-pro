package com.ses.service.impl;

import com.ses.dto.bpcompany.BpRiskSummaryDto;
import com.ses.entity.BpCompany;
import com.ses.entity.Notification;
import com.ses.entity.BpTerms;
import com.ses.entity.SysUser;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.BpRiskDashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BpRiskDashboardServiceImplTest {

    @Autowired
    private BpRiskDashboardService riskDashboardService;

    @Autowired
    private BpCompanyService bpCompanyService;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    @DisplayName("リスクサマリー集計および通知発行の検証")
    void testRiskSummaryAndNotifications() {
        // 1. 未確認法適用判定 + 低評価 (rating = 1) のBP会社
        BpCompany c1 = BpCompany.builder()
                .legalName("リスク会社1")
                .entityType("CORPORATE")
                .rating(1)
                .build();
        bpCompanyService.createBpCompany(c1);

        // 2. 取引停止 (SUSPENDED) + 60日超条件 (maxPaymentDays = 90) のBP会社
        BpCompany c2 = BpCompany.builder()
                .legalName("リスク会社2")
                .entityType("CORPORATE")
                .status("SUSPENDED")
                .complianceApplicability("FREELANCE_ACT")
                .build();
        bpCompanyService.createBpCompany(c2);

        BpTerms terms = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .paymentMonthOffset(3) // 3ヶ月後払い
                .paymentDay(30)
                .maxPaymentDays(90)
                .build();
        bpCompanyService.addTerms(c2.getId(), terms);

        // 集計検証
        BpRiskSummaryDto summary = riskDashboardService.getRiskSummary();
        assertTrue(summary.getUncheckedComplianceCount() >= 1);
        assertTrue(summary.getLowRatingCount() >= 1);
        assertTrue(summary.getSuspendedCount() >= 1);
        assertTrue(summary.getPaymentRiskCount() >= 1);

        // 通知生成検証
        int notificationCount = riskDashboardService.generateRiskNotifications();
        assertTrue(notificationCount >= 1);

        // 通知が実際に宛先ユーザーへ挿入されている（営業・管理者のみ）
        List<Notification> notifications = notificationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                        .eq(Notification::getMenuKey, "bp-company"));
        assertFalse(notifications.isEmpty(), "BPリスク通知が実際に挿入されていること");
        for (Notification n : notifications) {
            assertNotNull(n.getRecipientUserId(), "宛先は個人指定であること");
            SysUser recipient = sysUserMapper.selectById(n.getRecipientUserId());
            assertTrue(recipient != null
                            && List.of("営業", "管理者").contains(recipient.getRole()),
                    "通知宛先は営業または管理者のみ");
        }

        // 同一リスクの同日再実行では重複しない（dedupeKey）
        long before = notificationMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                        .eq(Notification::getMenuKey, "bp-company"));
        riskDashboardService.generateRiskNotifications();
        long after = notificationMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>()
                        .eq(Notification::getMenuKey, "bp-company"));
        assertEquals(before, after, "同一リスクの通知は1日1回まで");
    }
}
