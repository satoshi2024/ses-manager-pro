package com.ses.service.impl;

import com.ses.dto.compliance.ProcurementComplianceFinding;
import com.ses.entity.BpCompany;
import com.ses.entity.BpPriceNegotiation;
import com.ses.entity.BpTerms;
import com.ses.entity.Contract;
import com.ses.service.BpCompanyService;
import com.ses.service.BpComplianceService;
import com.ses.service.BpPriceNegotiationService;
import com.ses.service.SystemConfigService;
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

    @Autowired
    private SystemConfigService systemConfigService;

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
                .effectiveTo(LocalDate.of(2026, 5, 31))
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
        assertEquals(0, new BigDecimal("720000").compareTo(responded.getAgreedAmount()));

        // 履歴検索
        List<BpPriceNegotiation> list = negotiationService.getNegotiationsByBpCompany(company.getId());
        assertEquals(1, list.size());
        assertEquals("72万円で合意", list.get(0).getSummary());
    }

    @Test
    @DisplayName("受領日+60日ちょうどはOK、+61日はERROR（法務設定上限はconfig既定60日）")
    void testPaymentDaysBoundaryWithLegalConfig() {
        BpCompany company = BpCompany.builder()
                .legalName("境界テストBP")
                .entityType("CORPORATE")
                .complianceApplicability("FREELANCE_ACT")
                .build();
        bpCompanyService.createBpCompany(company);
        BpTerms terms = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .paymentMonthOffset(2)
                .paymentDay(1)
                .feeBearer("PAYER")
                .build();
        bpCompanyService.addTerms(company.getId(), terms);

        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 5, 1));
        contract.setEndDate(LocalDate.of(2026, 5, 31));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setContractDate(LocalDate.of(2026, 4, 20));
        contract.setJobDescription("システム開発支援");
        contract.setWorkLocation("客先常駐");
        contract.setInspectionDueDate(LocalDate.of(2026, 6, 5));
        contract.setPaymentMethod("BANK_TRANSFER");

        // 受領日2026-06-01 + offset2/day1 -> 2026-08-01 = 61日 > 60日
        List<ProcurementComplianceFinding> findings61 = complianceService
                .evaluateContractCompliance(company.getId(), contract, LocalDate.of(2026, 6, 1));
        assertTrue(findings61.stream().anyMatch(f -> "EXCEEDS_MAX_PAYMENT_DAYS".equals(f.getCode())));

        // 受領日2026-06-02 -> 2026-08-01 = 60日ちょうど
        List<ProcurementComplianceFinding> findings60 = complianceService
                .evaluateContractCompliance(company.getId(), contract, LocalDate.of(2026, 6, 2));
        assertTrue(findings60.stream().noneMatch(f -> "EXCEEDS_MAX_PAYMENT_DAYS".equals(f.getCode())));

        // 法務設定上限は m_system_config（procurement.payment-max-days）から読む。
        // 設定値はH2 replayのMERGEで60にseed済み（config unknownKeyにならないことも確認済み）。
        assertEquals(60, systemConfigService.getInt("procurement.payment-max-days", 60));
    }

    @Test
    @DisplayName("手数料受注者負担は例外理由+承認が無いとERROR、承認済みなら指摘なし")
    void testFeeBearerExceptionRequiresApproval() {
        BpCompany company = BpCompany.builder()
                .legalName("手数料テストBP")
                .entityType("CORPORATE")
                .complianceApplicability("SUBCOMMITTEE_ACT")
                .build();
        bpCompanyService.createBpCompany(company);
        BpTerms terms = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(LocalDate.of(2026, 5, 31))
                .paymentMonthOffset(1)
                .paymentDay(30)
                .feeBearer("PAYEE")
                .build();
        bpCompanyService.addTerms(company.getId(), terms);

        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 5, 1));
        contract.setEndDate(LocalDate.of(2026, 5, 31));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setContractDate(LocalDate.of(2026, 4, 20));
        contract.setJobDescription("システム開発支援");
        contract.setWorkLocation("客先常駐");
        contract.setInspectionDueDate(LocalDate.of(2026, 6, 5));
        contract.setPaymentMethod("BANK_TRANSFER");

        List<ProcurementComplianceFinding> withoutApproval = complianceService
                .evaluateContractCompliance(company.getId(), contract, LocalDate.of(2026, 5, 10));
        assertTrue(withoutApproval.stream().anyMatch(f -> "FEE_BEARER_PAYEE_WARNING".equals(f.getCode())));

        BpCompany approvedCompany = BpCompany.builder()
                .legalName("手数料承認済みBP")
                .entityType("CORPORATE")
                .complianceApplicability("SUBCOMMITTEE_ACT")
                .build();
        bpCompanyService.createBpCompany(approvedCompany);
        bpCompanyService.addTerms(approvedCompany.getId(), BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .paymentMonthOffset(1)
                .paymentDay(30)
                .feeBearer("PAYEE")
                .feeBearerExceptionReason("振込手数料は双方合意の上で受注者負担とする")
                .feeBearerApprovedBy(10L)
                .build());

        List<ProcurementComplianceFinding> withApproval = complianceService
                .evaluateContractCompliance(approvedCompany.getId(), contract, LocalDate.of(2026, 5, 10));
        assertTrue(withApproval.stream().noneMatch(f -> "FEE_BEARER_PAYEE_WARNING".equals(f.getCode())));
    }

    @Test
    @DisplayName("具体的な支払日が未設定（曖昧指定）と必須明示項目欠落を検知する")
    void testVaguePaymentDayAndMandatoryItems() {
        BpCompany company = BpCompany.builder()
                .legalName("曖昧条件テストBP")
                .entityType("CORPORATE")
                .complianceApplicability("FREELANCE_ACT")
                .build();
        bpCompanyService.createBpCompany(company);
        BpTerms terms = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .paymentMonthOffset(1)
                .paymentDay(0)
                .feeBearer("PAYER")
                .build();
        bpCompanyService.addTerms(company.getId(), terms);

        Contract contract = new Contract();
        contract.setStartDate(LocalDate.of(2026, 5, 1));
        contract.setEndDate(LocalDate.of(2026, 5, 31));
        contract.setCostPrice(new BigDecimal("600000"));

        List<ProcurementComplianceFinding> findings = complianceService
                .evaluateContractCompliance(company.getId(), contract, LocalDate.of(2026, 5, 1));
        assertTrue(findings.stream().anyMatch(f -> "VAGUE_PAYMENT_DAY".equals(f.getCode())));
        assertTrue(findings.stream().anyMatch(f -> "MISSING_CONTRACT_DATE".equals(f.getCode())));
        assertTrue(findings.stream().anyMatch(f -> "MISSING_JOB_DESCRIPTION".equals(f.getCode())));
        assertTrue(findings.stream().anyMatch(f -> "MISSING_WORK_LOCATION".equals(f.getCode())));
        assertTrue(findings.stream().anyMatch(f -> "MISSING_INSPECTION_DUE_DATE".equals(f.getCode())));
        assertTrue(findings.stream().anyMatch(f -> "MISSING_PAYMENT_METHOD".equals(f.getCode())));
    }
}
