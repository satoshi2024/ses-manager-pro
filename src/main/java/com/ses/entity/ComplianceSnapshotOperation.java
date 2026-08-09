package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * snapshot保存operationの冪等性管理。
 * content hashはidempotency keyにせず、operation_idとexpected current versionでretry/CASを担保する。
 * 同じoperationの再送は同じresulting snapshotを返し、新operationは同じcontentでも新versionを追加する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_snapshot_operation")
public class ComplianceSnapshotOperation extends BaseEntity {

    private String tenantId;
    private String operationId;
    private String scopeType;
    private Long contractId;
    private Long workerId;
    private Integer expectedVersion;
    private Long resultingSnapshotId;
    private Long resultingWorkerSnapshotId;
    private String requestHash;
    private String status;

    @Version
    private Integer version;
}
