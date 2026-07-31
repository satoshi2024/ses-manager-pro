package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.bpcompany.BpRiskSummaryDto;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.entity.SysUser;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpTermsMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.BpRiskDashboardService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BpRiskDashboardServiceImpl implements BpRiskDashboardService {

    private final BpCompanyMapper bpCompanyMapper;
    private final BpTermsMapper bpTermsMapper;
    private final NotificationService notificationService;
    private final SysUserMapper sysUserMapper;
    private final SystemConfigService systemConfigService;

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

        // 4. 法務設定上限（既定60日）を超える有効な支払条件を持つBP数
        int legalMaxDays = systemConfigService.getInt("procurement.payment-max-days", 60);
        LocalDate today = LocalDate.now();
        LambdaQueryWrapper<BpTerms> termsWrapper = new LambdaQueryWrapper<>();
        termsWrapper.le(BpTerms::getEffectiveFrom, today)
                .and(w -> w.isNull(BpTerms::getEffectiveTo).or().ge(BpTerms::getEffectiveTo, today));
        List<BpTerms> activeTerms = bpTermsMapper.selectList(termsWrapper);
        long paymentRiskCount = activeTerms.stream()
                .filter(t -> t.getPaymentDay() != null && t.getPaymentDay() > 0 && t.getPaymentDay() <= 31)
                .filter(t -> {
                    LocalDate due = today.plusMonths(t.getPaymentMonthOffset() != null ? t.getPaymentMonthOffset() : 1)
                            .withDayOfMonth(Math.min(t.getPaymentDay(), today.plusMonths(
                                    t.getPaymentMonthOffset() != null ? t.getPaymentMonthOffset() : 1).lengthOfMonth()));
                    return ChronoUnit.DAYS.between(today, due) > legalMaxDays;
                })
                .map(BpTerms::getBpCompanyId)
                .distinct()
                .count();

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
        Map<String, RiskNotification> risks = new LinkedHashMap<>();
        if (summary.getUncheckedComplianceCount() > 0) {
            risks.put("bp-compliance-unchecked-" + LocalDate.now(), new RiskNotification(
                    "WARNING",
                    "BPコンプライアンス警告",
                    String.format("法適用区分が未確認のBP会社が %d 件存在します。法務確認を実施してください。", summary.getUncheckedComplianceCount())));
        }
        if (summary.getPaymentRiskCount() > 0) {
            risks.put("bp-compliance-payment-risk-" + LocalDate.now(), new RiskNotification(
                    "DANGER",
                    "発注期日コンプライアンス警告",
                    String.format("法務設定上限を超える支払期日のBP条件が %d 件存在します。条件を見直してください。", summary.getPaymentRiskCount())));
        }
        if (risks.isEmpty()) {
            return 0;
        }

        // 宛先は担当営業と管理者のみ（個人指定）。組織一斉通知にしない。
        LambdaQueryWrapper<SysUser> recipientWrapper = new LambdaQueryWrapper<>();
        recipientWrapper.in(SysUser::getRole, "営業", "管理者")
                .eq(SysUser::getStatus, 1);
        List<SysUser> recipients = sysUserMapper.selectList(recipientWrapper);
        for (Map.Entry<String, RiskNotification> entry : risks.entrySet()) {
            RiskNotification risk = entry.getValue();
            for (SysUser recipient : recipients) {
                notificationService.publishToUser(recipient.getId(), risk.type(), risk.title(), risk.message(),
                        "/bp-company", entry.getKey(), "bp-company");
            }
        }
        return risks.size();
    }

    private record RiskNotification(String type, String title, String message) {
    }
}
