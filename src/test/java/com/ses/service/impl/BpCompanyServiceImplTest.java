package com.ses.service.impl;

import com.ses.dto.bpcompany.BpBankAccountDto;
import com.ses.dto.bpcompany.BpCompanyDto;
import com.ses.dto.bpcompany.BpTermsDto;
import com.ses.entity.BpBankAccount;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.mapper.BpBankAccountMapper;
import com.ses.service.BpCompanyService;
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
class BpCompanyServiceImplTest {

    @Autowired
    private BpCompanyService bpCompanyService;

    @Autowired
    private BpBankAccountMapper bpBankAccountMapper;

    @Test
    @DisplayName("BP会社の作成・取得とcomplianceApplicabilityがnullの時『未確認』であることの検証")
    void createAndGetBpCompany() {
        BpCompany company = BpCompany.builder()
                .tenantId(1L)
                .legalName("株式会社テストBP")
                .nameKana("カブシキガイシャテストビーピー")
                .entityType("CORPORATE")
                .corporateNumber("1234567890123")
                .invoiceRegistrationNumber("T1234567890123")
                .status("ACTIVE")
                .build();

        bpCompanyService.createBpCompany(company);
        assertNotNull(company.getId());

        BpCompanyDto detail = bpCompanyService.getBpCompanyDetail(company.getId());
        assertEquals("株式会社テストBP", detail.getLegalName());

        // complianceApplicability が null の場合は未確認状態であること
        assertNull(detail.getComplianceApplicability());

        // 法適用情報の更新
        bpCompanyService.updateComplianceApplicability(company.getId(), "FREELANCE_ACT", "フリーランス法適用対象", 10L);

        BpCompanyDto updatedDetail = bpCompanyService.getBpCompanyDetail(company.getId());
        assertEquals("FREELANCE_ACT", updatedDetail.getComplianceApplicability());
        assertEquals("フリーランス法適用対象", updatedDetail.getApplicabilityNote());
    }

    @Test
    @DisplayName("銀行口座の登録時に口座番号がマスクされ、復号値がDTOに漏洩しないことの検証")
    void bankAccountMaskingTest() {
        BpCompany company = BpCompany.builder()
                .legalName("口座テストBP")
                .entityType("CORPORATE")
                .build();
        bpCompanyService.createBpCompany(company);

        BpBankAccountDto account = bpCompanyService.addBankAccount(
                company.getId(),
                "三菱UFJ銀行",
                "本店営業部",
                "ORDINARY",
                "1234567",
                "テストタロウ",
                LocalDate.now(),
                null
        );

        assertNotNull(account.getId());
        assertEquals("****4567", account.getMaskedLabel());

        List<BpBankAccountDto> bankAccounts = bpCompanyService.getBankAccounts(company.getId());
        assertEquals(1, bankAccounts.size());
        BpBankAccountDto dto = bankAccounts.get(0);
        assertEquals("****4567", dto.getMaskedLabel());
        assertEquals("三菱UFJ銀行", dto.getBankName());

        // 保存値は平文・Base64ではなくAES暗号文であること（復号値は通常APIで返さない）
        BpBankAccount stored = bpBankAccountMapper.selectById(account.getId());
        assertNotNull(stored.getEncryptedAccountNumber());
        assertNotEquals("1234567", stored.getEncryptedAccountNumber());
        assertFalse(stored.getEncryptedAccountNumber().contains("1234567"));
        assertEquals("PENDING", stored.getApprovalStatus());

        // 承認状態CAS: PENDING -> APPROVED は成功し、2回目（二重承認）は拒否される
        bpCompanyService.updateBankAccountApproval(account.getId(), "APPROVED", 10L);
        BpBankAccount approved = bpBankAccountMapper.selectById(account.getId());
        assertEquals("APPROVED", approved.getApprovalStatus());
        org.junit.jupiter.api.Assertions.assertThrows(
                com.ses.common.exception.BusinessException.class,
                () -> bpCompanyService.updateBankAccountApproval(account.getId(), "REJECTED", 10L));
    }

    @Test
    @DisplayName("BpTermsの支払確定日基準でのEffective判定テスト")
    void bpTermsResolverTest() {
        BpCompany company = BpCompany.builder()
                .legalName("条件テストBP")
                .entityType("CORPORATE")
                .build();
        bpCompanyService.createBpCompany(company);

        // 過去の条件 (2026-01-01 ～ 2026-06-30): 翌月末払い (30日)
        BpTerms term1 = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(LocalDate.of(2026, 6, 30))
                .closingDay(31)
                .paymentMonthOffset(1)
                .paymentDay(30)
                .maxPaymentDays(60)
                .build();
        bpCompanyService.addTerms(company.getId(), term1);

        // 新条件 (2026-07-01 ～): 当月末払い (15日)
        BpTerms term2 = BpTerms.builder()
                .effectiveFrom(LocalDate.of(2026, 7, 1))
                .effectiveTo(null)
                .closingDay(31)
                .paymentMonthOffset(0)
                .paymentDay(15)
                .maxPaymentDays(45)
                .build();
        bpCompanyService.addTerms(company.getId(), term2);

        // 2026-05-15 時点での解決 -> 過去条件 (term1)
        BpTermsDto activeMay = bpCompanyService.getActiveTermsAsOf(company.getId(), LocalDate.of(2026, 5, 15));
        assertNotNull(activeMay);
        assertEquals(60, activeMay.getMaxPaymentDays());
        assertEquals(1, activeMay.getPaymentMonthOffset());

        // 2026-07-15 時点での解決 -> 新条件 (term2)
        BpTermsDto activeJuly = bpCompanyService.getActiveTermsAsOf(company.getId(), LocalDate.of(2026, 7, 15));
        assertNotNull(activeJuly);
        assertEquals(45, activeJuly.getMaxPaymentDays());
        assertEquals(0, activeJuly.getPaymentMonthOffset());
    }
}
