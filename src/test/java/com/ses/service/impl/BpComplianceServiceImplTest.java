package com.ses.service.impl;

import com.ses.dto.compliance.ProcurementComplianceFinding;
import com.ses.entity.BpCompany;
import com.ses.entity.BpPriceNegotiation;
import com.ses.entity.BpTerms;
import com.ses.entity.Contract;
import com.ses.service.BpCompanyService;
import com.ses.service.BpComplianceService;
import com.ses.service.BpPriceNegotiationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BpComplianceServiceImplTest {

    @Autowired
    private BpComplianceService complianceService;

    @Autowired
    private BpCompanyService bpCompanyService;

    @Autowired
    private BpPriceNegotiationService negotiationService;

    @Test
    @DisplayName("60日境界値テストおよび手数料負担・未確認警告の検証")
    void testCompliance60DaysBoundaryAndWarnings() {
        // 1. BP会社作成 (法適用区分未確認)
        BpCompany company = BpCompany.builder()
                .legalName("コンプライアンステストBP")
                .entityType("CORPORATE")
                .build();
        bpCompanyService.createBpCompany(company);

        // 条件設定: 翌月末払い (offset=1, day=30), 手数料受注者負担 (PAYEE)
        BpTerms terms = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .paymentMonthOffset(1)
                .paymentDay(30)
                .feeBearer("PAYEE")
                .maxPaymentDays(60)
                .build();
        bpCompanyService.addTerms(company.getId(), terms);

        // 契約データ作成
        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 5, 1));
        contract.setEndDate(LocalDate.of(2026, 5, 31));
        contract.setCostPrice(new BigDecimal("600000"));

        // 1. 翌月末払い (offset=1, day=30): 受領日 2026-04-01 -> 支払日 2026-05-30 は 59日 (60日以内)
        List<ProcurementComplianceFinding> findings59Days = complianceService.evaluateContractCompliance(company.getId(), contract, LocalDate.of(2026, 4, 1));
        assertTrue(findings59Days.stream().noneMatch(f -> "EXCEEDS_MAX_PAYMENT_DAYS".equals(f.getCode())));
        assertTrue(findings59Days.stream().anyMatch(f -> "UNCHECKED_COMPLIANCE_APPLICABILITY".equals(f.getCode())));
        assertTrue(findings59Days.stream().anyMatch(f -> "FEE_BEARER_PAYEE_WARNING".equals(f.getCode())));

        // 2. 翌々月末払い (offset=2, day=30) 条件を追加
        BpTerms longTerms = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 6, 1))
                .paymentMonthOffset(2)
                .paymentDay(30)
                .maxPaymentDays(60)
                .build();
        bpCompanyService.addTerms(company.getId(), longTerms);

        // 受領日 2026-06-01 -> 支払日 2026-08-30 は 90日 (60日超過)
        List<ProcurementComplianceFinding> findings90Days = complianceService.evaluateContractCompliance(company.getId(), contract, LocalDate.of(2026, 6, 1));
        assertTrue(findings90Days.stream().anyMatch(f -> "EXCEEDS_MAX_PAYMENT_DAYS".equals(f.getCode())));
    }

    @Test
    @DisplayName("価格協議の要請、回答、履歴記録の検証")
    void testPriceNegotiationFlow() {
        BpCompany company = BpCompany.builder()
                .legalName("協議テストBP")
                .entityType("CORPORATE")
                .build();
        bpCompanyService.createBpCompany(company);

        // 価格協議要請
        BpPriceNegotiation req = negotiationService.requestNegotiation(company.getId(), new BigDecimal("750000"), "単価増額改定要求", null);
        assertNotNull(req.getId());
        assertEquals("REQUESTED", req.getStatus());

        // 価格協議回答・合意
        BpPriceNegotiation responded = negotiationService.respondNegotiation(req.getId(), "AGREED", new BigDecimal("720000"), "72万円で合意");
        assertEquals("AGREED", responded.getStatus());
        assertEquals(new BigDecimal("720000"), responded.getAgreedAmount());

        // 履歴検索
        List<BpPriceNegotiation> list = negotiationService.getNegotiationsByBpCompany(company.getId());
        assertEquals(1, list.size());
        assertEquals("72万円で合意", list.get(0).getSummary());
    }
}
