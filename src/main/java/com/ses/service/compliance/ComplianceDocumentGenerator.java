package com.ses.service.compliance;

import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceSnapshot;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * T064 B1: 法定帳票の内容モデル構築とPDF生成（MAPPING-2026-07 baseline）。
 * 帳票はcontract compliance snapshotのtyped列から生成し、current masterを再読しない（design §5.1）。
 * sensitive行（待遇・保険・苦情詳細・雇用安定措置・抵触日例外・worker PII）は
 * maskLevel != FULL の場合に「—」へ置換する（R4.2: export/PDFも同じfield permission）。
 * 法的適否は自動確定せず、項目の対応だけを出力する（T060前提節）。
 */
@Component
public class ComplianceDocumentGenerator {

    /** 帳票種別定数（MissingDocumentDeliveryRuleと共有）。 */
    public static final String TYPE_EMPLOYMENT_CONDITIONS = "EMPLOYMENT_CONDITIONS_STATEMENT";
    public static final String TYPE_DISPATCH_NOTICE = "DISPATCH_NOTICE";
    public static final String TYPE_DISPATCH_LEDGER = "DISPATCH_LEDGER";
    public static final String TYPE_INDIVIDUAL_CONTRACT = "INDIVIDUAL_CONTRACT";

    public static final java.util.Set<String> DOCUMENT_TYPES = java.util.Set.of(
            TYPE_EMPLOYMENT_CONDITIONS, TYPE_DISPATCH_NOTICE, TYPE_DISPATCH_LEDGER, TYPE_INDIVIDUAL_CONTRACT);

    private static final String MASK_VALUE = "—";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 帳票の1行。labelKeyはi18nキー、valueは表示値、sensitiveはmask対象か。 */
    public record Row(String labelKey, String value, boolean sensitive) {
    }

    /** 帳票の1section。titleKeyはi18nキー。 */
    public record Section(String titleKey, List<Row> rows) {
    }

    /** 帳票内容モデル（テストでgolden照合する）。 */
    public record Content(String titleKey, List<Section> sections) {
    }

    private final com.ses.common.util.PdfFontUtils pdfFontUtils;

    public ComplianceDocumentGenerator(com.ses.common.util.PdfFontUtils pdfFontUtils) {
        this.pdfFontUtils = pdfFontUtils;
    }

    /**
     * snapshotから帳票内容を構築する。
     *
     * @param engineerName   派遣労働者氏名（ledger用。worker snapshot未作成時は契約要員名）
     * @param workerSnapshot worker snapshot（任意。存在すればworker固有項目を出力する）
     */
    public Content build(Contract contract, ContractComplianceSnapshot snapshot, String documentType,
                         String maskLevel, String engineerName,
                         com.ses.entity.ContractComplianceWorkerSnapshot workerSnapshot) {
        if (!DOCUMENT_TYPES.contains(documentType)) {
            throw new IllegalArgumentException("unknown document type: " + documentType);
        }
        boolean masked = !"FULL".equals(maskLevel);
        List<Section> sections = switch (documentType) {
            case TYPE_EMPLOYMENT_CONDITIONS -> employmentConditions(snapshot, masked);
            case TYPE_DISPATCH_NOTICE -> dispatchNotice(snapshot, masked);
            case TYPE_DISPATCH_LEDGER -> dispatchLedger(contract, snapshot, masked, engineerName, workerSnapshot);
            case TYPE_INDIVIDUAL_CONTRACT -> individualContract(snapshot, masked);
            default -> List.of();
        };
        return new Content("doc.title." + documentType, sections);
    }

    /** 内容モデルをPDF（A4・日本語フォント埋め込み）へ変換する。 */
    public byte[] toPdf(Content content, MessageSource messageSource) {
        Locale locale = LocaleContextHolder.getLocale();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             com.lowagie.text.Document document = new com.lowagie.text.Document(
                     com.lowagie.text.PageSize.A4, 36, 36, 36, 36)) {
            com.lowagie.text.pdf.PdfWriter.getInstance(document, baos);
            document.open();
            com.lowagie.text.pdf.BaseFont baseFont = pdfFontUtils.resolveCjkFont();
            com.lowagie.text.Font titleFont = new com.lowagie.text.Font(baseFont, 14, com.lowagie.text.Font.BOLD);
            com.lowagie.text.Font headerFont = new com.lowagie.text.Font(baseFont, 11, com.lowagie.text.Font.BOLD);
            com.lowagie.text.Font normalFont = new com.lowagie.text.Font(baseFont, 9, com.lowagie.text.Font.NORMAL);

            com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph(
                    msg(messageSource, content.titleKey(), content.titleKey()), titleFont);
            title.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            title.setSpacingAfter(16);
            document.add(title);

            for (Section section : content.sections()) {
                com.lowagie.text.Paragraph header = new com.lowagie.text.Paragraph(
                        msg(messageSource, section.titleKey(), section.titleKey()), headerFont);
                header.setSpacingBefore(10);
                header.setSpacingAfter(4);
                document.add(header);
                for (Row row : section.rows()) {
                    String label = msg(messageSource, row.labelKey(), row.labelKey());
                    String value = row.value() == null ? "" : row.value();
                    com.lowagie.text.Paragraph line = new com.lowagie.text.Paragraph(
                            label + ": " + value, normalFont);
                    line.setSpacingAfter(2);
                    document.add(line);
                }
            }
            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("帳票PDFの生成に失敗しました", e);
        }
    }

    private String msg(MessageSource messageSource, String key, String fallback) {
        if (messageSource == null) {
            return key;
        }
        return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    // ===== 帳票種別別の構成 =====

    private List<Section> employmentConditions(ContractComplianceSnapshot s, boolean masked) {
        List<Section> sections = new ArrayList<>();
        sections.add(section("doc.section.workplace", List.of(
                row("doc.party.name", s.getPartyName(), false),
                row("doc.party.address", s.getPartyAddress(), false),
                row("doc.party.representative", s.getPartyRepresentative(), false),
                row("doc.party.dispatchTo", s.getWorkplaceName(), false),
                row("doc.address", s.getWorkplaceAddress(), false),
                row("doc.department", s.getWorkplaceDepartment(), false),
                row("doc.workplacePhone", s.getWorkplacePhone(), false))));
        sections.add(section("doc.section.work", List.of(
                row("cpp.workDescription", s.getWorkDescription(), false),
                row("doc.period", period(s.getDispatchFrom(), s.getDispatchTo()), false),
                row("cpp.statutoryJobFlag", flag(s.getStatutoryJobFlag()), false),
                row("cpp.statutoryJobReference", s.getStatutoryJobReference(), false))));
        sections.add(section("doc.section.worktime", List.of(
                row("cpp.workStartMinute", minutes(s.getWorkStartMinute()), false),
                row("cpp.workEndMinute", minutes(s.getWorkEndMinute()), false),
                row("cpp.breakStartMinute", minutes(s.getBreakStartMinute()), false),
                row("cpp.breakEndMinute", minutes(s.getBreakEndMinute()), false),
                row("cpp.workDayCode", s.getWorkDayCode(), false),
                row("cpp.holidayCalendarCode", s.getHolidayCalendarCode(), false),
                row("cpp.overtimeDailyLimit", s.getOvertimeDailyLimit() == null ? null : s.getOvertimeDailyLimit() + "h", false),
                row("cpp.overtimeMonthlyLimit", s.getOvertimeMonthlyLimit() == null ? null : s.getOvertimeMonthlyLimit() + "h", false),
                row("cpp.overtimeYearlyLimit", s.getOvertimeYearlyLimit() == null ? null : s.getOvertimeYearlyLimit() + "h", false))));
        sections.add(section("doc.section.wage", List.of(
                row("cpp.dispatchFeeAmount", fee(s.getDispatchFeeAmount()), true),
                row("cpp.dispatchFeeBasis", s.getDispatchFeeBasis(), true),
                row("cpp.dispatchFeeCurrency", s.getDispatchFeeCurrency(), true))));
        sections.add(section("doc.section.insurance", List.of(
                row("doc.socialInsuranceReason", s.getSocialInsuranceProcedureIncompleteReason(), true),
                row("cpp.healthInsuranceStatus", s.getHealthInsuranceStatus(), true),
                row("cpp.pensionInsuranceStatus", s.getPensionInsuranceStatus(), true),
                row("cpp.employmentInsuranceStatus", s.getEmploymentInsuranceStatus(), true))));
        sections.add(section("doc.section.benefits", List.of(
                row("cpp.benefitsDetail", s.getBenefitsDetail(), true),
                row("cpp.benefitsProvidedFlag", flag(s.getBenefitsProvidedFlag()), true),
                row("cpp.agreementTargetFlag", flag(s.getAgreementTargetFlag()), false),
                row("cpp.treatmentScheme", s.getTreatmentScheme(), true),
                row("cpp.employmentStabilityPreference", s.getEmploymentStabilityPreference(), true))));
        sections.add(section("doc.section.limitation", List.of(
                row("cpp.workplaceLimitationDate", date(s.getWorkplaceLimitationDate()), false),
                row("cpp.organizationLimitationDate", date(s.getOrganizationLimitationDate()), false),
                row("cpp.limitationExemptionType", s.getLimitationExemptionType(), true),
                row("cpp.limitationExemptionDetail", s.getLimitationExemptionDetail(), true),
                row("cpp.limitationExemptionBasis", s.getLimitationExemptionBasis(), true),
                row("cpp.limitationExemptionFrom", date(s.getLimitationExemptionFrom()), true),
                row("cpp.limitationExemptionTo", date(s.getLimitationExemptionTo()), true))));
        sections.add(section("doc.section.safety", List.of(
                row("cpp.safetyResponsibilityDetail", s.getSafetyResponsibilityDetail(), false),
                row("cpp.safetyRuleReference", s.getSafetyRuleReference(), false))));
        sections.add(section("doc.section.complaint", List.of(
                row("cpp.sourceComplaintContactName", s.getSourceComplaintContactName(), true),
                row("cpp.sourceComplaintContactPhone", s.getSourceComplaintContactPhone(), true),
                row("cpp.clientComplaintContactName", s.getClientComplaintContactName(), true),
                row("cpp.clientComplaintContactPhone", s.getClientComplaintContactPhone(), true))));
        sections.add(section("doc.section.responsible", List.of(
                row("cpp.commandPersonName", s.getCommandPersonName(), false),
                row("cpp.commandPersonDepartment", s.getCommandPersonDepartment(), false),
                row("cpp.commandPersonPhone", s.getCommandPersonPhone(), false))));
        return mask(sections, masked);
    }

    private List<Section> dispatchNotice(ContractComplianceSnapshot s, boolean masked) {
        List<Section> sections = new ArrayList<>();
        sections.add(section("doc.section.workplace", List.of(
                row("doc.party.name", s.getPartyName(), false),
                row("doc.party.address", s.getPartyAddress(), false),
                row("doc.party.representative", s.getPartyRepresentative(), false),
                row("doc.party.dispatchTo", s.getWorkplaceName(), false),
                row("doc.address", s.getWorkplaceAddress(), false),
                row("doc.department", s.getWorkplaceDepartment(), false))));
        sections.add(section("doc.section.work", List.of(
                row("cpp.workDescription", s.getWorkDescription(), false),
                row("doc.period", period(s.getDispatchFrom(), s.getDispatchTo()), false))));
        sections.add(section("doc.section.worktime", List.of(
                row("cpp.workStartMinute", minutes(s.getWorkStartMinute()), false),
                row("cpp.workEndMinute", minutes(s.getWorkEndMinute()), false),
                row("cpp.breakStartMinute", minutes(s.getBreakStartMinute()), false),
                row("cpp.breakEndMinute", minutes(s.getBreakEndMinute()), false),
                row("cpp.workDayCode", s.getWorkDayCode(), false),
                row("cpp.overtimeDailyLimit", s.getOvertimeDailyLimit() == null ? null : s.getOvertimeDailyLimit() + "h", false),
                row("cpp.overtimeMonthlyLimit", s.getOvertimeMonthlyLimit() == null ? null : s.getOvertimeMonthlyLimit() + "h", false),
                row("cpp.overtimeYearlyLimit", s.getOvertimeYearlyLimit() == null ? null : s.getOvertimeYearlyLimit() + "h", false))));
        sections.add(section("doc.section.responsible", List.of(
                row("cpp.commandPersonName", s.getCommandPersonName(), false),
                row("cpp.commandPersonDepartment", s.getCommandPersonDepartment(), false),
                row("cpp.clientResponsibleName", s.getClientResponsibleName(), false),
                row("cpp.clientResponsibleDepartment", s.getClientResponsibleDepartment(), false),
                row("cpp.dispatchResponsibleName", s.getDispatchResponsibleName(), false),
                row("cpp.dispatchResponsibleDepartment", s.getDispatchResponsibleDepartment(), false))));
        sections.add(section("doc.section.wage", List.of(
                row("cpp.dispatchFeeAmount", fee(s.getDispatchFeeAmount()), true),
                row("cpp.dispatchFeeBasis", s.getDispatchFeeBasis(), true))));
        sections.add(section("doc.section.benefits", List.of(
                row("cpp.benefitsDetail", s.getBenefitsDetail(), true),
                row("cpp.agreementTargetFlag", flag(s.getAgreementTargetFlag()), false),
                row("cpp.treatmentScheme", s.getTreatmentScheme(), true))));
        sections.add(section("doc.section.safety", List.of(
                row("cpp.safetyResponsibilityDetail", s.getSafetyResponsibilityDetail(), false),
                row("cpp.safetyRuleReference", s.getSafetyRuleReference(), false))));
        sections.add(section("doc.section.complaint", List.of(
                row("cpp.sourceComplaintContactName", s.getSourceComplaintContactName(), true),
                row("cpp.sourceComplaintContactPhone", s.getSourceComplaintContactPhone(), true),
                row("cpp.clientComplaintContactName", s.getClientComplaintContactName(), true),
                row("cpp.clientComplaintContactPhone", s.getClientComplaintContactPhone(), true))));
        return mask(sections, masked);
    }

    private List<Section> dispatchLedger(Contract contract, ContractComplianceSnapshot s, boolean masked,
                                         String engineerName,
                                         com.ses.entity.ContractComplianceWorkerSnapshot worker) {
        List<Section> sections = new ArrayList<>();
        List<Row> workerRows = new ArrayList<>();
        workerRows.add(row("doc.workerName", engineerName, true));
        workerRows.add(row("doc.contractNo", s.getContractNo(), false));
        workerRows.add(row("doc.period", period(s.getDispatchFrom(), s.getDispatchTo()), false));
        if (worker != null) {
            // worker snapshot由来項目（T066 Mで帳票全項目化。worker snapshot未作成時は出力しない）
            workerRows.add(row("doc.workerGender", worker.getGender(), true));
            workerRows.add(row("doc.workerAgeBand", worker.getAgeBand(), true));
            workerRows.add(row("doc.workerEmploymentTerm", worker.getEmploymentTermType(), true));
            workerRows.add(row("doc.workerEmploymentFrom", date(worker.getEmploymentFrom()), true));
            workerRows.add(row("doc.workerEmploymentTo", date(worker.getEmploymentTo()), true));
            workerRows.add(row("doc.workerIndefinite", flag(worker.getIndefiniteWorkerFlag()), true));
            workerRows.add(row("doc.workerOver60", flag(worker.getAgeOver60Flag()), true));
            workerRows.add(row("doc.workerRestriction", worker.getWorkerRestrictionType(), true));
        }
        sections.add(section("doc.section.worker", workerRows));
        sections.add(section("doc.section.work", List.of(
                row("cpp.workDescription", s.getWorkDescription(), false),
                row("cpp.workDayCode", s.getWorkDayCode(), false),
                row("cpp.workStartMinute", minutes(s.getWorkStartMinute()), false),
                row("cpp.workEndMinute", minutes(s.getWorkEndMinute()), false),
                row("cpp.overtimeMonthlyLimit", s.getOvertimeMonthlyLimit() == null ? null : s.getOvertimeMonthlyLimit() + "h", false))));
        sections.add(section("doc.section.responsible", List.of(
                row("cpp.clientResponsibleName", s.getClientResponsibleName(), false),
                row("cpp.dispatchResponsibleName", s.getDispatchResponsibleName(), false))));
        sections.add(section("doc.section.insurance", List.of(
                row("cpp.healthInsuranceStatus", s.getHealthInsuranceStatus(), true),
                row("cpp.pensionInsuranceStatus", s.getPensionInsuranceStatus(), true),
                row("cpp.employmentInsuranceStatus", s.getEmploymentInsuranceStatus(), true))));
        sections.add(section("doc.section.wage", List.of(
                row("cpp.dispatchFeeAmount", fee(s.getDispatchFeeAmount()), true),
                row("cpp.dispatchFeeBasis", s.getDispatchFeeBasis(), true))));
        sections.add(section("doc.section.complaint", List.of(
                row("cpp.sourceComplaintContactName", s.getSourceComplaintContactName(), true),
                row("cpp.clientComplaintContactName", s.getClientComplaintContactName(), true))));
        sections.add(section("doc.section.benefits", List.of(
                row("cpp.employmentStabilityPreference", s.getEmploymentStabilityPreference(), true),
                row("cpp.benefitsDetail", s.getBenefitsDetail(), true))));
        sections.add(section("doc.section.limitation", List.of(
                row("cpp.workplaceLimitationDate", date(s.getWorkplaceLimitationDate()), false),
                row("cpp.organizationLimitationDate", date(s.getOrganizationLimitationDate()), false),
                row("cpp.limitationExemptionType", s.getLimitationExemptionType(), true))));
        sections.add(section("doc.section.retention", List.of(
                row("cpp.retentionDueDate", date(s.getRetentionDueDate()), false))));
        return mask(sections, masked);
    }

    private List<Section> individualContract(ContractComplianceSnapshot s, boolean masked) {
        List<Section> sections = new ArrayList<>();
        sections.add(section("doc.section.workplace", List.of(
                row("doc.party.name", s.getPartyName(), false),
                row("doc.party.address", s.getPartyAddress(), false),
                row("doc.party.representative", s.getPartyRepresentative(), false),
                row("doc.party.dispatchTo", s.getWorkplaceName(), false),
                row("doc.address", s.getWorkplaceAddress(), false))));
        sections.add(section("doc.section.work", List.of(
                row("cpp.workDescription", s.getWorkDescription(), false),
                row("doc.period", period(s.getDispatchFrom(), s.getDispatchTo()), false),
                row("cpp.responsibilityLevel", s.getResponsibilityLevel(), false),
                row("cpp.responsibilityDetail", s.getResponsibilityDetail(), false))));
        sections.add(section("doc.section.worktime", List.of(
                row("cpp.workStartMinute", minutes(s.getWorkStartMinute()), false),
                row("cpp.workEndMinute", minutes(s.getWorkEndMinute()), false),
                row("cpp.breakStartMinute", minutes(s.getBreakStartMinute()), false),
                row("cpp.breakEndMinute", minutes(s.getBreakEndMinute()), false),
                row("cpp.workDayCode", s.getWorkDayCode(), false),
                row("cpp.overtimeMonthlyLimit", s.getOvertimeMonthlyLimit() == null ? null : s.getOvertimeMonthlyLimit() + "h", false))));
        sections.add(section("doc.section.responsible", List.of(
                row("cpp.commandPersonName", s.getCommandPersonName(), false),
                row("cpp.clientResponsibleName", s.getClientResponsibleName(), false),
                row("cpp.dispatchResponsibleName", s.getDispatchResponsibleName(), false))));
        sections.add(section("doc.section.wage", List.of(
                row("cpp.dispatchFeeAmount", fee(s.getDispatchFeeAmount()), true),
                row("cpp.dispatchFeeBasis", s.getDispatchFeeBasis(), true),
                row("cpp.dispatchFeeCurrency", s.getDispatchFeeCurrency(), true))));
        sections.add(section("doc.section.benefits", List.of(
                row("cpp.benefitsDetail", s.getBenefitsDetail(), true),
                row("cpp.dispatchHeadcount", s.getDispatchHeadcount() == null ? null : String.valueOf(s.getDispatchHeadcount()), false),
                row("cpp.agreementTargetFlag", flag(s.getAgreementTargetFlag()), false),
                row("cpp.treatmentScheme", s.getTreatmentScheme(), true),
                row("cpp.employmentStabilityPreference", s.getEmploymentStabilityPreference(), true))));
        sections.add(section("doc.section.complaint", List.of(
                row("cpp.sourceComplaintContactName", s.getSourceComplaintContactName(), true),
                row("cpp.clientComplaintContactName", s.getClientComplaintContactName(), true))));
        sections.add(section("doc.section.limitation", List.of(
                row("cpp.workplaceLimitationDate", date(s.getWorkplaceLimitationDate()), false),
                row("cpp.organizationLimitationDate", date(s.getOrganizationLimitationDate()), false),
                row("cpp.limitationExemptionType", s.getLimitationExemptionType(), true),
                row("cpp.limitationExemptionDetail", s.getLimitationExemptionDetail(), true),
                row("cpp.limitationExemptionBasis", s.getLimitationExemptionBasis(), true),
                row("cpp.limitationExemptionFrom", date(s.getLimitationExemptionFrom()), true),
                row("cpp.limitationExemptionTo", date(s.getLimitationExemptionTo()), true))));
        sections.add(section("doc.section.quasi", List.of(
                row("cpp.instructionRoute", s.getInstructionRoute(), false),
                row("cpp.subcontractAllowed", flag(s.getSubcontractAllowed()), false),
                row("cpp.acceptanceMethod", s.getAcceptanceMethod(), false))));
        return mask(sections, masked);
    }

    private List<Section> mask(List<Section> sections, boolean masked) {
        if (!masked) {
            return sections;
        }
        List<Section> result = new ArrayList<>();
        for (Section section : sections) {
            List<Row> rows = section.rows().stream()
                    .map(row -> row.sensitive() ? new Row(row.labelKey(), MASK_VALUE, true) : row)
                    .toList();
            result.add(new Section(section.titleKey(), rows));
        }
        return result;
    }

    private Section section(String titleKey, List<Row> rows) {
        return new Section(titleKey, rows);
    }

    private Row row(String labelKey, String value, boolean sensitive) {
        return new Row(labelKey, value, sensitive);
    }

    private String minutes(Integer minute) {
        if (minute == null) {
            return null;
        }
        return String.format("%02d:%02d", minute / 60, minute % 60);
    }

    private String fee(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private String date(LocalDate value) {
        return value == null ? null : value.format(DATE);
    }

    private String period(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return null;
        }
        return (from == null ? "?" : from.format(DATE)) + " 〜 " + (to == null ? "?" : to.format(DATE));
    }

    private String flag(Integer value) {
        if (value == null) {
            return null;
        }
        return value == 1 ? "○" : "×";
    }
}
