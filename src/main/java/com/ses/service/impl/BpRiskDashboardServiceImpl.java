package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.bpcompany.BpRiskSummaryDto;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpTermsMapper;
import com.ses.service.BpRiskDashboardService;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BpRiskDashboardServiceImpl implements BpRiskDashboardService {

    private final BpCompanyMapper bpCompanyMapper;
    private final BpTermsMapper bpTermsMapper;
    private final NotificationService notificationService;

    @Override
    public BpRiskSummaryDto getRiskSummary() {
        // 1. 未確認法適用判定数
        LambdaQueryWrapper<BpCompany> uncheckedWrapper = new LambdaQueryWrapper<>();
        uncheckedWrapper.isNull(BpCompany::getComplianceApplicability)
                .or()
                .eq(BpCompany::getComplianceApplicability, "");
        long uncheckedCount = bpCompanyMapper.selectCount(uncheckedWrapper);

        // 2. 低評価BP数 (rating <= 2)
        LambdaQueryWrapper<BpCompany> lowRatingWrapper = new LambdaQueryWrapper<>();
        lowRatingWrapper.le(BpCompany::getRating, 2);
        long lowRatingCount = bpCompanyMapper.selectCount(lowRatingWrapper);

        // 3. 取引停止数
        LambdaQueryWrapper<BpCompany> suspendedWrapper = new LambdaQueryWrapper<>();
        suspendedWrapper.eq(BpCompany::getStatus, "SUSPENDED");
        long suspendedCount = bpCompanyMapper.selectCount(suspendedWrapper);

        // 4. 60日超支払リスク数 (maxPaymentDays > 60 または (offset * 30 > 60))
        LambdaQueryWrapper<BpTerms> termsWrapper = new LambdaQueryWrapper<>();
        termsWrapper.gt(BpTerms::getMaxPaymentDays, 60)
                .or()
                .gt(BpTerms::getPaymentMonthOffset, 2);
        long paymentRiskCount = bpTermsMapper.selectCount(termsWrapper);

        return BpRiskSummaryDto.builder()
                .uncheckedComplianceCount(uncheckedCount)
                .lowRatingCount(lowRatingCount)
                .suspendedCount(suspendedCount)
                .paymentRiskCount(paymentRiskCount)
                .build();
    }

    @Override
    public int generateRiskNotifications() {
        BpRiskSummaryDto summary = getRiskSummary();
        int count = 0;

        if (summary.getUncheckedComplianceCount() > 0) {
            notificationService.publish(
                    "WARNING",
                    "BPコンプライアンス警告",
                    String.format("法適用区分が未確認のBP会社が %d 件存在します。法務確認を実施してください。", summary.getUncheckedComplianceCount()),
                    "/bp-company",
                    "bp-compliance-unchecked-" + System.currentTimeMillis()
            );
            count++;
        }

        if (summary.getPaymentRiskCount() > 0) {
            notificationService.publish(
                    "DANGER",
                    "発注期日コンプライアンス警告",
                    String.format("60日を超える支払期日が設定されたBP条件が %d 件存在します。条件を見直してください。", summary.getPaymentRiskCount()),
                    "/bp-company",
                    "bp-compliance-payment-risk-" + System.currentTimeMillis()
            );
            count++;
        }

        return count;
    }
}
