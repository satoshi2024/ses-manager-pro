package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * worker-specificのappend-only snapshot。
 * UNIQUE(contract_id, worker_id, snapshot_version)。current pointerは
 * t_contract_compliance_worker_state がFK/CASで管理し、2 workerのpointerは独立する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract_compliance_worker_snapshot")
public class ContractComplianceWorkerSnapshot extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private Long workerId;
    private Integer snapshotVersion;
    private String snapshotHash;
    private String operationId;
    private LocalDateTime snapshotAt;

    /** WORKER_PII_SNAPSHOT */
    private String workerName;
    private String employerName;
    private String employerAddress;
    private String employerTitle;
    private String gender;
    private String ageBand;
    private LocalDate ageAtReferenceDate;

    /** WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT */
    private String employmentTermType;
    private LocalDate employmentFrom;
    private LocalDate employmentTo;
    private Integer indefiniteWorkerFlag;
    /** DB列名は `age_over_60_flag`（spec field-mapping §4 / F1MapManifest正本）。camelCase変換では age_over60_flag になるため明示する。 */
    @TableField("age_over_60_flag")
    private Integer ageOver60Flag;
    private String workerRestrictionType;

    /** INSURANCE_TYPED（worker別） */
    private String healthInsuranceStatus;
    private String healthInsuranceMissingReason;
    private LocalDate healthInsuranceExpectedDate;
    private String pensionInsuranceStatus;
    private String pensionInsuranceMissingReason;
    private LocalDate pensionInsuranceExpectedDate;
    private String employmentInsuranceStatus;
    private String employmentInsuranceMissingReason;
    private LocalDate employmentInsuranceExpectedDate;

    @Version
    private Integer version;
}
