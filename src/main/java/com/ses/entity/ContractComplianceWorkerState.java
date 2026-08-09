package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * workerごとのcurrent pointer。current_snapshot_idは
 * t_contract_compliance_worker_snapshotへのFK、versionがCAS対象。
 * worker A/Bのpointerは独立して更新される。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract_compliance_worker_state")
public class ContractComplianceWorkerState extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private Long workerId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long currentSnapshotId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer currentSnapshotVersion;

    @Version
    private Integer version;
}
