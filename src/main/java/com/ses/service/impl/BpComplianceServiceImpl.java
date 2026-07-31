package com.ses.service.impl;

import com.ses.dto.compliance.ProcurementComplianceFinding;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.entity.Contract;
import com.ses.mapper.BpCompanyMapper;
import com.ses.service.BpComplianceService;
import com.ses.service.BpTermsResolver;
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

        // 1. 法適用区分未確認チェック
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

        if (contract.getStartDate() == null || contract.getEndDate() == null) {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_SERVICE_PERIOD")
                    .severity("ERROR")
                    .message("役務の提供期間(開始日・終了日)が未指定です")
                    .field("period")
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

        // 3. 支払期日・受領日+60日超え判定
        LocalDate refDate = receiptDate != null ? receiptDate : (contract.getStartDate() != null ? contract.getStartDate() : LocalDate.now());
        BpTerms terms = termsResolver.resolveTermsAsOf(bpCompanyId, refDate);

        if (terms != null) {
            int offset = terms.getPaymentMonthOffset();
            int day = terms.getPaymentDay();

            // 支払予定日の概算計算 (例: 5月受領、1ヶ月オフセット30日 -> 6月30日)
            LocalDate paymentDueDate = refDate.plusMonths(offset).withDayOfMonth(Math.min(day, refDate.plusMonths(offset).lengthOfMonth()));

            long daysBetween = ChronoUnit.DAYS.between(refDate, paymentDueDate);
            int maxDays = terms.getMaxPaymentDays() != null ? terms.getMaxPaymentDays() : 60;

            if (daysBetween > maxDays) {
                findings.add(ProcurementComplianceFinding.builder()
                        .code("EXCEEDS_MAX_PAYMENT_DAYS")
                        .severity("ERROR")
                        .message(String.format("受領日(%s)から支払日(%s)までの日数(%d日)が法定上限(%d日)を超過しています", refDate, paymentDueDate, daysBetween, maxDays))
                        .field("paymentDueDate")
                        .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                        .build());
            }

            // 4. 振込手数料負担チェック
            if ("PAYEE".equalsIgnoreCase(terms.getFeeBearer())) {
                findings.add(ProcurementComplianceFinding.builder()
                        .code("FEE_BEARER_PAYEE_WARNING")
                        .severity("WARNING")
                        .message("振込手数料を受注者(BP)負担とする場合、書面による事前合意と正当な理由の記録が必要です")
                        .field("feeBearer")
                        .sourceUrl(SOURCE_URL_FREELANCE)
                        .build());
            }
        } else {
            findings.add(ProcurementComplianceFinding.builder()
                    .code("MISSING_TERMS")
                    .severity("ERROR")
                    .message("発注確定日時点で有効な支払条件(BpTerms)が設定されていません")
                    .field("terms")
                    .sourceUrl(SOURCE_URL_SUBCOMMITTEE)
                    .build());
        }

        return findings;
    }
}
