package com.ses.service.impl;

import com.ses.dto.bpcompany.BpRiskSummaryDto;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.service.BpCompanyService;
import com.ses.service.BpRiskDashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BpRiskDashboardServiceImplTest {

    @Autowired
    private BpRiskDashboardService riskDashboardService;

    @Autowired
    private BpCompanyService bpCompanyService;

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
    }
}
