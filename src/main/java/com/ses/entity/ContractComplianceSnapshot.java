package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 契約compliance profileのappend-only snapshot。
 * UNIQUE(contract_id, snapshot_version)のみが一意制約。snapshot_hashは内容hashの非一意索引であり、
 * retryの冪等性キーにしない（operation tableが担当）。A(v1,hA)→B(v2,hB)→A(v3,hA)を3 version保持する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract_compliance_snapshot")
public class ContractComplianceSnapshot extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private Integer snapshotVersion;
    private String snapshotHash;
    private String operationId;
    private LocalDateTime snapshotAt;

    /** CONTRACT_PARTY_PERIOD_SNAPSHOT */
    private String contractNo;
    private LocalDate contractDate;
    private String partyName;
    private String partyAddress;
    private String partyRepresentative;
    private LocalDate dispatchFrom;
    private LocalDate dispatchTo;

    /** WORKPLACE_ORG_SNAPSHOT */
    private String workplaceName;
    private String workplaceAddress;
    private String workplaceDepartment;
    private String workplacePhone;
    private String organizationUnit;
    private String organizationHeadTitle;

    /** WORK_DESCRIPTION_TYPED */
    private String workDescription;
    private Integer statutoryJobFlag;
    private String statutoryJobReference;

    /** RESPONSIBILITY_TYPED */
    private String responsibilityLevel;
    private String responsibilityDetail;
    private String commandPersonDepartment;
    private String commandPersonTitle;
    private String commandPersonName;
    private String commandPersonPhone;
    private String clientResponsibleDepartment;
    private String clientResponsibleTitle;
    private String clientResponsibleName;
    private String clientResponsiblePhone;
    private String dispatchResponsibleDepartment;
    private String dispatchResponsibleTitle;
    private String dispatchResponsibleName;
    private String dispatchResponsiblePhone;

    /** WORK_TIME_TYPED */
    private Integer workStartMinute;
    private Integer workEndMinute;
    private Integer workSpanNextDayFlag;
    /** 休憩開始/終了（分整数）。複数休憩は t_compliance_break_detail の反復行 */
    private Integer breakStartMinute;
    private Integer breakEndMinute;

    /** WORK_CALENDAR_HISTORY */
    private String workDayCode;
    private String holidayCalendarCode;

    /** OVERTIME_AGREEMENT_SNAPSHOT */
    private Long agreementReferenceId;
    private Integer overtimeDailyLimit;
    private Integer overtimeMonthlyLimit;
    private Integer overtimeYearlyLimit;
    private LocalDate overtimePeriodFrom;
    private LocalDate overtimePeriodTo;

    /** LIMITATION_DUAL_TYPED（NULL=未算定） */
    private LocalDate workplaceLimitationDate;
    private LocalDate organizationLimitationDate;

    /** SAFETY_TYPED */
    private String safetyResponsibilityDetail;
    private String safetyRuleReference;

    /** BENEFITS_TYPED */
    private String benefitsDetail;
    private Integer benefitsProvidedFlag;

    /** HEADCOUNT_TYPED */
    private Integer dispatchHeadcount;

    /** AGREEMENT_FLAG_TYPED */
    private Integer agreementTargetFlag;
    private String treatmentScheme;

    /** COMPLAINT_HISTORY（current窓口snapshot） */
    private String sourceComplaintContactDepartment;
    private String sourceComplaintContactTitle;
    private String sourceComplaintContactName;
    private String sourceComplaintContactPhone;
    private String clientComplaintContactDepartment;
    private String clientComplaintContactTitle;
    private String clientComplaintContactName;
    private String clientComplaintContactPhone;

    /** EMPLOYMENT_STABILITY_HISTORY */
    private String employmentStabilityPreference;

    /** LIMITATION_EXEMPTION_TYPED */
    private String limitationExemptionType;
    private String limitationExemptionDetail;
    private String limitationExemptionBasis;
    private LocalDate limitationExemptionFrom;
    private LocalDate limitationExemptionTo;

    /** DISPATCH_FEE_TYPED */
    private BigDecimal dispatchFeeAmount;
    private String dispatchFeeBasis;
    private String dispatchFeeCurrency;

    /** INSURANCE_TYPED */
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

    /** 準委任/請負 */
    private String instructionRoute;
    private Integer subcontractAllowed;
    private String acceptanceMethod;

    /** RETENTION_METADATA */
    private LocalDate retentionDueDate;
    private Integer legalHoldFlag;

    @Version
    private Integer version;
}
