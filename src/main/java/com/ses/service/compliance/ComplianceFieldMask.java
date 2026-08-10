package com.ses.service.compliance;

import com.ses.entity.ContractComplianceProfile;

import java.lang.reflect.Field;
import java.util.List;

/**
 * compliance profileのfield mask定数（design §5.3・field-mapping §2.1）。
 * 画面（T063）、帳票/export/download/PDF（T064）で同一のallow-listを共有する。
 *  - P0_FULL: 管理者/HRは全field
 *  - P1_MASK: マネージャーはSENSITIVE_FIELDSをmask
 *  - P2_LIMITED: 営業はP2_ALLOWED_FIELDSのみ
 */
public final class ComplianceFieldMask {

    private ComplianceFieldMask() {
    }

    /** マネージャー（P1_MASK）でmaskするsensitive field（待遇・保険・苦情詳細・雇用安定措置・抵触日例外）。 */
    public static final List<String> SENSITIVE_FIELDS = List.of(
            "dispatchFeeAmount", "dispatchFeeBasis", "dispatchFeeCurrency",
            "benefitsDetail", "benefitsProvidedFlag",
            "treatmentScheme",
            "socialInsuranceProcedureIncompleteReason",
            "healthInsuranceStatus", "healthInsuranceMissingReason", "healthInsuranceExpectedDate",
            "pensionInsuranceStatus", "pensionInsuranceMissingReason", "pensionInsuranceExpectedDate",
            "employmentInsuranceStatus", "employmentInsuranceMissingReason", "employmentInsuranceExpectedDate",
            "sourceComplaintContactDepartment", "sourceComplaintContactTitle",
            "sourceComplaintContactName", "sourceComplaintContactPhone",
            "clientComplaintContactDepartment", "clientComplaintContactTitle",
            "clientComplaintContactName", "clientComplaintContactPhone",
            "employmentStabilityPreference",
            "limitationExemptionType", "limitationExemptionDetail", "limitationExemptionBasis",
            "limitationExemptionFrom", "limitationExemptionTo");

    /** 営業（P2_LIMITED）が見られる限定field（契約遂行に必要な業務項目）。 */
    public static final List<String> P2_ALLOWED_FIELDS = List.of(
            "contractTypeDetail", "workplaceId",
            "workDescription", "statutoryJobFlag", "statutoryJobReference",
            "responsibilityLevel", "responsibilityDetail",
            "commandPersonContactId", "commandPersonDepartment", "commandPersonTitle",
            "commandPersonName", "commandPersonPhone",
            "clientResponsibleContactId", "clientResponsibleDepartment", "clientResponsibleTitle",
            "clientResponsibleName", "clientResponsiblePhone",
            "dispatchResponsibleUserId", "dispatchResponsibleDepartment", "dispatchResponsibleTitle",
            "dispatchResponsibleName", "dispatchResponsiblePhone",
            "workStartMinute", "workEndMinute", "workSpanNextDayFlag",
            "breakStartMinute", "breakEndMinute",
            "workDayCode", "holidayCalendarCode",
            "agreementReferenceId",
            "overtimeDailyLimit", "overtimeMonthlyLimit", "overtimeYearlyLimit",
            "overtimePeriodFrom", "overtimePeriodTo",
            "workplaceLimitationDate", "organizationLimitationDate",
            "safetyResponsibilityDetail", "safetyRuleReference",
            "dispatchHeadcount", "agreementTargetFlag",
            "instructionRoute", "subcontractAllowed", "acceptanceMethod",
            "dispatchPeriodStart", "dispatchPeriodEnd");

    /** T066 gateまでのserver管理field（画面・帳票の編集対象にしない）。 */
    public static final List<String> SERVER_MANAGED_FIELDS = List.of(
            "retentionDueDate", "legalHoldFlag");

    /** profile entityのfieldをnull化する（mask適用）。 */
    public static void maskField(ContractComplianceProfile profile, String fieldName) {
        try {
            Field field = ContractComplianceProfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(profile, null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("mask対象fieldがentityに存在しません: " + fieldName, e);
        }
    }
}
