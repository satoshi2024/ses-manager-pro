package com.ses.service.impl;

import com.ses.dto.compliance.ProcurementComplianceFinding;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.entity.Contract;
import com.ses.mapper.BpCompanyMapper;
import com.ses.service.BpComplianceService;
import com.ses.service.BpTermsResolver;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BpComplianceServiceImpl implements BpComplianceService {

    private final BpCompanyMapper bpCompanyMapper;
    private final BpTermsResolver termsResolver;
    private final SystemConfigService systemConfigService;

    private static final String SOURCE_URL_FREELANCE = "https://www.jftc.go.jp/freelancelaw_2025/";
    private static final String SOURCE_URL_SUBCOMMITTEE = "https://www.jftc.go.jp/toriteki/";

    @Override
    public List<ProcurementComplianceFinding> evaluateContractCompliance(Long bpCompanyId, Contract contract, LocalDate receiptDate) {
        List<ProcurementComplianceFinding> findings = new ArrayList<>();

        BpCompany company = bpCompanyMapper.selectById(bpCompanyId);
        if (company == null) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("INVALID_BP_COMPANY")
                    .severity("ERROR")
                    .message("BP会社が存在しません")
                    .sourceUrl(SOURCE_URL_FREELANCE)
                    .build());
            return findings;
        }

        // 1. 法適用区分未確認チェック（明示NULL＝未確認であり「非該当」ではない）
        if (!StringUtils.hasText(company.getComplianceApplicability())) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("UNCHECKED_COMPLIANCE_APPLICABILITY")
                    .severity("WARNING")
                    .message("法適用区分が未確認です。社内責任者による法務確認を完了してください")
                    .field("complianceApplicability")
                    .sourceUrl(SOURCE_URL_FREELANCE)
                    .build());
        }

        // 2. 必須明示項目の検証
        if (contract == null) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_CONTRACT")
                    .severity("ERROR")
                    .message("発注契約情報が提供されていません")
                    .build());
            return findings;
        }

        if (contract.getContractDate() == null) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_CONTRACT_DATE")
                    .severity("ERROR")
                    .message("委託日が未指定です")
                    .field("contractDate")
                    .sourceUrl(SOURCE_URL_FREELANCE)
                    .build());
        }

        if (contract.getStartDate() == null || contract.getEndDate() == null) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_SERVICE_PERIOD")
                    .severity("ERROR")
                    .message("役務の提供期間(開始日・終了日)が未指定です")
                    .field("period")
                    .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                    .build());
        }

        if (!StringUtils.hasText(contract.getJobDescription())) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_JOB_DESCRIPTION")
                    .severity("ERROR")
                    .message("役務内容が未指定です")
                    .field("jobDescription")
                    .sourceUrl(SOURCE_URL_FREELANCE)
                    .build());
        }

        if (!StringUtils.hasText(contract.getWorkLocation())) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_WORK_LOCATION")
                    .severity("ERROR")
                    .message("提供場所が未指定です")
                    .field("workLocation")
                    .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                    .build());
        }

        if (contract.getInspectionDueDate() == null) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_INSPECTION_DUE_DATE")
                    .severity("ERROR")
                    .message("検査完了期日が未指定です")
                    .field("inspectionDueDate")
                    .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                    .build());
        }

        if (!StringUtils.hasText(contract.getPaymentMethod())) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_PAYMENT_METHOD")
                    .severity("ERROR")
                    .message("支払方法が未指定です")
                    .field("paymentMethod")
                    .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                    .build());
        }

        if (contract.getCostPrice() == null || contract.getCostPrice().compareTo(BigDecimal.ZERO) <= 0) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_REMUNERATION")
                    .severity("ERROR")
                    .message("発注金額(原価/報酬額)が正しく指定されていません")
                    .field("costPrice")
                    .sourceUrl(SOURCE_URL_FREELANCE)
                    .build());
        }

        // 3. 支払期日・受領日+法務設定上限（既定60日）超え判定
        LocalDate refDate = receiptDate != null ? receiptDate : (contract.getStartDate() != null ? contract.getStartDate() : LocalDate.now());
        BpTerms terms = termsResolver.resolveTermsAsOf(bpCompanyId, refDate);
        int legalMaxDays = systemConfigService.getInt("procurement.payment-max-days", 60);

        if (terms != null) {
            int offset = terms.getPaymentMonthOffset() != null ? terms.getPaymentMonthOffset() : 1;
            Integer day = terms.getPaymentDay();

            if (day == null || day <= 0 || day > 31) {
                findings.add(ProcurementComplianceFinding.builder()
                        .code("VAGUE_PAYMENT_DAY")
                        .severity("ERROR")
                        .message("具体的な支払日（1〜31日）が未設定です。「翌月末」等の曖昧指定は不可です")
                        .field("paymentDay")
                        .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                        .build());
            }

            LocalDate paymentDueDate = contract.getPaymentDueDate();
            if (paymentDueDate == null && day != null && day > 0 && day <= 31) {
                // 支払予定日の概算計算 (例: 5月受領、1ヶ月オフセット30日 -> 6月30日)
                paymentDueDate = refDate.plusMonths(offset).withDayOfMonth(Math.min(day, refDate.plusMonths(offset).lengthOfMonth()));
            }

            if (paymentDueDate == null) {
                findings.add(ProcurementComplianceFinding.builder()
                        .code("MISSING_PAYMENT_DUE_DATE")
                        .severity("ERROR")
                        .message("具体的な支払期日が未指定です")
                        .field("paymentDueDate")
                        .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                        .build());
            } else {
                long daysBetween = ChronoUnit.DAYS.between(refDate, paymentDueDate);
                if (daysBetween > legalMaxDays) {
                    findings.add(ProcurementComplianceFinding.builder()
                            .code("EXCEEDS_MAX_PAYMENT_DAYS")
                            .severity("ERROR")
                            .message(String.format("受領日(%s)から支払日(%s)までの日数(%d日)が法務設定上限(%d日)を超過しています",
                                    refDate, paymentDueDate, daysBetween, legalMaxDays))
                            .field("paymentDueDate")
                            .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                            .build());
                }
            }

            // 4. 振込手数料負担チェック（例外理由と承認記録が無い場合のみ指摘）
            if ("PAYEE".equalsIgnoreCase(terms.getFeeBearer())) {
                boolean approvedException = StringUtils.hasText(terms.getFeeBearerExceptionReason())
                        && terms.getFeeBearerApprovedBy() != null;
                if (!approvedException) {
                    findings.add(ProcurementComplianceFinding.builder()
                            .code("FEE_BEARER_PAYEE_WARNING")
                            .severity("ERROR")
                            .message("振込手数料を受注者(BP)負担とする場合、書面による事前合意、正当な理由、承認記録が必須です")
                            .field("feeBearer")
                            .sourceUrl(SOURCE_URL_FREELANCE)
                            .build());
                }
            }
        } else if (contract.getPaymentDueDate() == null) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_TERMS")
                    .severity("ERROR")
                    .message("発注確定日時点で有効な支払条件(BpTerms)が設定されておらず、具体的な支払期日も未指定です")
                    .field("terms")
                    .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                    .build());
        }

        return findings;
    }
}
