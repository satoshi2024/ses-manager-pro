package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 契約ごとの派遣・準委任コンプライアンス項目（mutable current row）。
 * 帳票再生成用の不変値は t_contract_compliance_snapshot へversion付きで保存する。
 * 明示NULL（値→NULL）はclear可能なnullable列のみ、full DTO＋FieldStrategy.ALWAYSで行う
 * （design §5.5 explicit NULL / field-mapping §4.3。省略PATCHはT063のAPIでrejectする）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract_compliance_profile")
public class ContractComplianceProfile extends BaseEntity {

    private String tenantId;
    private Long contractId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String contractTypeDetail;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long workplaceId;

    /** WORK_DESCRIPTION_TYPED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String workDescription;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer statutoryJobFlag;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String statutoryJobReference;

    /** RESPONSIBILITY_TYPED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String responsibilityLevel;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String responsibilityDetail;

    /** 指揮命令者（RESPONSIBILITY_TYPED） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long commandPersonContactId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String commandPersonDepartment;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String commandPersonTitle;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String commandPersonName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String commandPersonPhone;

    /** 派遣先責任者（RESPONSIBILITY_TYPED） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long clientResponsibleContactId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientResponsibleDepartment;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientResponsibleTitle;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientResponsibleName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientResponsiblePhone;

    /** 派遣元責任者（RESPONSIBILITY_TYPED） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long dispatchResponsibleUserId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dispatchResponsibleDepartment;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dispatchResponsibleTitle;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dispatchResponsibleName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dispatchResponsiblePhone;

    /** WORK_TIME_TYPED（分整数。0=00:00） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer workStartMinute;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer workEndMinute;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer workSpanNextDayFlag;
    /** 休憩開始/終了（分整数）。複数休憩は t_compliance_break_detail の反復行 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer breakStartMinute;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer breakEndMinute;

    /** WORK_CALENDAR_HISTORY（current部分。除外日は t_compliance_work_calendar） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String workDayCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String holidayCalendarCode;

    /** OVERTIME_AGREEMENT_SNAPSHOT */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long agreementReferenceId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer overtimeDailyLimit;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer overtimeMonthlyLimit;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer overtimeYearlyLimit;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate overtimePeriodFrom;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate overtimePeriodTo;

    /** LIMITATION_DUAL_TYPED（NULL=未算定。「抵触日なし」ではない） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate workplaceLimitationDate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate organizationLimitationDate;

    /** SAFETY_TYPED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String safetyResponsibilityDetail;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String safetyRuleReference;

    /** BENEFITS_TYPED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String benefitsDetail;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer benefitsProvidedFlag;

    /** HEADCOUNT_TYPED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer dispatchHeadcount;

    /** AGREEMENT_FLAG_TYPED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer agreementTargetFlag;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String treatmentScheme;

    /** COMPLAINT_HISTORY（current窓口。実際の申出は t_compliance_complaint_history） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceComplaintContactDepartment;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceComplaintContactTitle;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceComplaintContactName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceComplaintContactPhone;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientComplaintContactDepartment;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientComplaintContactTitle;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientComplaintContactName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String clientComplaintContactPhone;

    /** EMPLOYMENT_STABILITY_HISTORY（current希望） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String employmentStabilityPreference;

    /** LIMITATION_EXEMPTION_TYPED */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String limitationExemptionType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String limitationExemptionDetail;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String limitationExemptionBasis;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate limitationExemptionFrom;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate limitationExemptionTo;

    /** DISPATCH_FEE_TYPED（売上/粗利列とは分離） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal dispatchFeeAmount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dispatchFeeBasis;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dispatchFeeCurrency;

    /** INSURANCE_TYPED（SRC-E⑱単一理由＋3保険別status/reason/expected_date） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String socialInsuranceProcedureIncompleteReason;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String healthInsuranceStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String healthInsuranceMissingReason;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate healthInsuranceExpectedDate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pensionInsuranceStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pensionInsuranceMissingReason;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate pensionInsuranceExpectedDate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String employmentInsuranceStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String employmentInsuranceMissingReason;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate employmentInsuranceExpectedDate;

    /** 準委任/請負 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String instructionRoute;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer subcontractAllowed;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String acceptanceMethod;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate dispatchPeriodStart;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate dispatchPeriodEnd;

    /** RETENTION_METADATA */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate retentionDueDate;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer legalHoldFlag;

    /** current snapshot pointer（FK/CAS） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long currentSnapshotId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer currentSnapshotVersion;

    @Version
    private Integer version;
}
