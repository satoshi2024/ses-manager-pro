package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 契約ごとの派遣・準委任コンプライアンス項目と帳票用snapshot。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract_compliance_profile")
public class ContractComplianceProfile extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private String contractTypeDetail;
    private Long workplaceId;
    private String workDescription;
    private String workLocation;
    private String workTime;
    private String breakTime;
    private String holidayRule;
    private String overtimeRule;
    private Long commandPersonContactId;
    private String commandPersonNameSnapshot;
    private String commandPersonTitleSnapshot;
    private Long clientResponsibleContactId;
    private String clientResponsiblePerson;
    private String clientResponsiblePhone;
    private Long dispatchResponsibleUserId;
    private String dispatchResponsibleNameSnapshot;
    private String dispatchResponsibleTitleSnapshot;
    private String dispatchResponsiblePhoneSnapshot;
    private LocalDate dispatchPeriodStart;
    private LocalDate dispatchPeriodEnd;
    private LocalDate limitationDate;
    private LocalDate workplaceLimitationDate;
    private LocalDate workerLimitationDate;
    private String treatmentScheme;
    private String complaintContact;
    private String complaintProcessingHistory;
    private String trainingInfo;
    private String safetyHealthInfo;
    private String insuranceNotification;
    private String welfareInfo;
    private String instructionRoute;
    private String responsibilityDegree;
    private Integer subcontractAllowed;
    private String acceptanceMethod;
    private Integer dispatchWorkerCount;
    private Integer agreementTargetFlag;
    private Integer indefiniteTermFlag;
    private Integer ageOver60Flag;
    private String employmentStabilityMeasure;
    private String healthInsuranceStatus;
    private String healthInsuranceMissingReason;
    private LocalDate healthInsuranceExpectedDate;
    private String pensionInsuranceStatus;
    private String pensionInsuranceMissingReason;
    private LocalDate pensionInsuranceExpectedDate;
    private String employmentInsuranceStatus;
    private String employmentInsuranceMissingReason;
    private LocalDate employmentInsuranceExpectedDate;
    private String snapshotJson;
    private String workplaceSnapshotJson;
    private String workerSnapshotJson;
    private LocalDateTime snapshotAt;

    @Version
    private Integer version;
}
