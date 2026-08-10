package com.ses.service.compliance;

import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T064 B1: 帳票内容モデルのgolden照合（L2）。
 *  - 4帳票種別の構成（section/row）がfield-mapping（MAPPING-2026-07 baseline）どおりであること
 *  - maskLevel != FULL でsensitive row（待遇・保険・苦情・雇用安定・worker PII）が「—」になること
 *  - PDF生成が正常にバイト列を返すこと
 */
class ComplianceDocumentGeneratorTest {

    private final ComplianceDocumentGenerator generator = new ComplianceDocumentGenerator(
            new com.ses.common.util.PdfFontUtils(new com.ses.config.PdfProperties()));

    private Contract contract() {
        Contract contract = new Contract();
        contract.setId(1L);
        contract.setContractNo("C-100");
        contract.setCustomerId(10L);
        contract.setEngineerId(100L);
        contract.setContractType("派遣");
        return contract;
    }

    private ContractComplianceSnapshot snapshot() {
        ContractComplianceSnapshot s = new ContractComplianceSnapshot();
        s.setContractId(1L);
        s.setSnapshotVersion(1);
        s.setSnapshotHash("h1");
        s.setContractNo("C-100");
        s.setPartyName("SES株式会社");
        s.setWorkplaceName("顧客株式会社");
        s.setWorkplaceAddress("東京都千代田区1-1-1");
        s.setWorkplaceDepartment("開発部");
        s.setWorkDescription("システム開発");
        s.setDispatchFrom(LocalDate.of(2026, 1, 1));
        s.setDispatchTo(LocalDate.of(2026, 12, 31));
        s.setWorkStartMinute(540);
        s.setWorkEndMinute(1080);
        s.setBreakStartMinute(720);
        s.setBreakEndMinute(780);
        s.setWorkDayCode("月〜金");
        s.setOvertimeDailyLimit(2);
        s.setOvertimeMonthlyLimit(30);
        s.setCommandPersonName("田中指揮");
        s.setClientResponsibleName("鈴木責任");
        s.setDispatchResponsibleName("佐藤自社");
        s.setDispatchFeeAmount(new BigDecimal("120000"));
        s.setDispatchFeeBasis("月額");
        s.setHealthInsuranceStatus("加入済み");
        s.setPensionInsuranceStatus("加入済み");
        s.setEmploymentInsuranceStatus("加入済み");
        s.setSocialInsuranceProcedureIncompleteReason("手続中");
        s.setSourceComplaintContactName("苦情窓口A");
        s.setClientComplaintContactName("苦情窓口B");
        s.setEmploymentStabilityPreference("継続就業支援を希望");
        s.setWorkplaceLimitationDate(LocalDate.of(2029, 1, 1));
        s.setOrganizationLimitationDate(LocalDate.of(2027, 1, 1));
        s.setSafetyResponsibilityDetail("安全衛生責任は派遣元");
        s.setInstructionRoute("PM経由で指示");
        s.setSubcontractAllowed(0);
        s.setAcceptanceMethod("月次検収");
        return s;
    }

    @Test
    void 就業条件明示書はFULLで全行sensitive値を含みMASKでは待遇保険苦情がマスクされる() {
        ComplianceDocumentGenerator.Content full = generator.build(contract(), snapshot(),
                ComplianceDocumentGenerator.TYPE_EMPLOYMENT_CONDITIONS, "FULL", "山田太郎");
        assertThat(full.titleKey()).isEqualTo("doc.title.EMPLOYMENT_CONDITIONS_STATEMENT");
        assertThat(rows(full, "doc.section.wage"))
                .extracting(row -> row.value())
                .contains("120000", "月額");
        assertThat(rows(full, "doc.section.insurance"))
                .extracting(row -> row.value())
                .contains("加入済み", "手続中");
        assertThat(rows(full, "doc.section.worktime"))
                .extracting(row -> row.value())
                .contains("09:00", "18:00", "12:00", "13:00");

        ComplianceDocumentGenerator.Content masked = generator.build(contract(), snapshot(),
                ComplianceDocumentGenerator.TYPE_EMPLOYMENT_CONDITIONS, "MASK", "山田太郎");
        assertThat(rows(masked, "doc.section.wage"))
                .extracting(row -> row.value())
                .containsOnly("—");
        assertThat(rows(masked, "doc.section.insurance"))
                .extracting(row -> row.value())
                .containsOnly("—");
        assertThat(rows(masked, "doc.section.complaint"))
                .extracting(row -> row.value())
                .containsOnly("—");
        // 非sensitive（業務内容・就業時間・責任者）はMASKでも見える
        assertThat(rows(masked, "doc.section.work"))
                .extracting(row -> row.value())
                .contains("システム開発");
        assertThat(rows(masked, "doc.section.responsible"))
                .extracting(row -> row.value())
                .contains("田中指揮");
    }

    @Test
    void 派遣元管理台帳はworker氏名がsensitiveとしてマスクされる() {
        ComplianceDocumentGenerator.Content full = generator.build(contract(), snapshot(),
                ComplianceDocumentGenerator.TYPE_DISPATCH_LEDGER, "FULL", "山田太郎");
        assertThat(rows(full, "doc.section.worker"))
                .extracting(row -> row.value())
                .contains("山田太郎");

        ComplianceDocumentGenerator.Content masked = generator.build(contract(), snapshot(),
                ComplianceDocumentGenerator.TYPE_DISPATCH_LEDGER, "MASK", "山田太郎");
        assertThat(rows(masked, "doc.section.worker"))
                .filteredOn(row -> row.labelKey().equals("doc.workerName"))
                .extracting(row -> row.value())
                .containsExactly("—");
        // 非sensitive（契約No・期間）はMASKでも見える
        assertThat(rows(masked, "doc.section.worker"))
                .filteredOn(row -> row.labelKey().equals("doc.contractNo"))
                .extracting(row -> row.value())
                .containsExactly("C-100");
    }

    @Test
    void 派遣先通知書と個別契約書も構成される() {
        ComplianceDocumentGenerator.Content notice = generator.build(contract(), snapshot(),
                ComplianceDocumentGenerator.TYPE_DISPATCH_NOTICE, "FULL", "山田太郎");
        assertThat(notice.titleKey()).isEqualTo("doc.title.DISPATCH_NOTICE");
        assertThat(rows(notice, "doc.section.responsible"))
                .extracting(row -> row.value())
                .contains("田中指揮", "鈴木責任", "佐藤自社");

        ComplianceDocumentGenerator.Content individual = generator.build(contract(), snapshot(),
                ComplianceDocumentGenerator.TYPE_INDIVIDUAL_CONTRACT, "FULL", "山田太郎");
        assertThat(individual.titleKey()).isEqualTo("doc.title.INDIVIDUAL_CONTRACT");
        assertThat(rows(individual, "doc.section.quasi"))
                .extracting(row -> row.value())
                .contains("PM経由で指示", "月次検収");
    }

    @Test
    void 未知の帳票種別はIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> generator.build(contract(), snapshot(), "UNKNOWN_TYPE", "FULL", "山田太郎"));
    }

    @Test
    void toPdfは日本語PDFバイト列を返す() {
        ComplianceDocumentGenerator.Content content = generator.build(contract(), snapshot(),
                ComplianceDocumentGenerator.TYPE_EMPLOYMENT_CONDITIONS, "FULL", "山田太郎");
        byte[] pdf = generator.toPdf(content, null);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("%PDF");
    }

    private java.util.List<ComplianceDocumentGenerator.Row> rows(ComplianceDocumentGenerator.Content content, String sectionTitleKey) {
        return content.sections().stream()
                .filter(section -> section.titleKey().equals(sectionTitleKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("section not found: " + sectionTitleKey))
                .rows();
    }
}
