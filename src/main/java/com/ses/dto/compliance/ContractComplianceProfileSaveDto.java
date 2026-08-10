package com.ses.dto.compliance;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 契約compliance profileの保存リクエスト（T063 A1）。
 * 全編集可能fieldを必ず含むfull DTO。省略はrejectする（design §5.5・R8-P2-01）。
 * マネージャー（masked role）はsensitive fieldを省略可（省略=現値維持、異なる値=reject）。
 * currentSnapshotId/Version・tenantId・contractIdはserver管理のため含まない。
 */
@Data
public class ContractComplianceProfileSaveDto {

    private Integer version;

    private String contractTypeDetail;
    private Long workplaceId;

    private String workDescription;
    private Integer statutoryJobFlag;
    private String statutoryJobReference;

    private String responsibilityLevel;
    private String responsibilityDetail;

    private Long commandPersonContactId;
    private String commandPersonDepartment;
    private String commandPersonTitle;
    private String commandPersonName;
    private String commandPersonPhone;

    private Long clientResponsibleContactId;
    private String clientResponsibleDepartment;
    private String clientResponsibleTitle;
    private String clientResponsibleName;
    private String clientResponsiblePhone;

    private Long dispatchResponsibleUserId;
    private String dispatchResponsibleDepartment;
    private String dispatchResponsibleTitle;
    private String dispatchResponsibleName;
    private String dispatchResponsiblePhone;

    private Integer workStartMinute;
    private Integer workEndMinute;
    private Integer workSpanNextDayFlag;
    private Integer breakStartMinute;
    private Integer breakEndMinute;

    private String workDayCode;
    private String holidayCalendarCode;

    private Long agreementReferenceId;
    private Integer overtimeDailyLimit;
    private Integer overtimeMonthlyLimit;
    private Integer overtimeYearlyLimit;
    private LocalDate overtimePeriodFrom;
    private LocalDate overtimePeriodTo;

    private LocalDate workplaceLimitationDate;
    private LocalDate organizationLimitationDate;

    private String safetyResponsibilityDetail;
    private String safetyRuleReference;

    private String benefitsDetail;
    private Integer benefitsProvidedFlag;

    private Integer dispatchHeadcount;

    private Integer agreementTargetFlag;
    private String treatmentScheme;

    private String sourceComplaintContactDepartment;
    private String sourceComplaintContactTitle;
    private String sourceComplaintContactName;
    private String sourceComplaintContactPhone;
    private String clientComplaintContactDepartment;
    private String clientComplaintContactTitle;
    private String clientComplaintContactName;
    private String clientComplaintContactPhone;

    private String employmentStabilityPreference;

    private String limitationExemptionType;
    private String limitationExemptionDetail;
    private String limitationExemptionBasis;
    private LocalDate limitationExemptionFrom;
    private LocalDate limitationExemptionTo;

    private BigDecimal dispatchFeeAmount;
    private String dispatchFeeBasis;
    private String dispatchFeeCurrency;

    private String socialInsuranceProcedureIncompleteReason;
    private String healthInsuranceStatus;
    private String healthInsuranceMissingReason;
    private LocalDate healthInsuranceExpectedDate;
    private String pensionInsuranceStatus;
    private String pensionInsuranceMissingReason;
    private LocalDate pensionInsuranceExpectedDate;
    private String employmentInsuranceStatus;
    private String employmentInsuranceMissingReason;
    private LocalDate employmentInsuranceExpectedDate;

    private String instructionRoute;
    private Integer subcontractAllowed;
    private String acceptanceMethod;

    private LocalDate dispatchPeriodStart;
    private LocalDate dispatchPeriodEnd;

    private LocalDate retentionDueDate;
    private Integer legalHoldFlag;
}
