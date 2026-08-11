package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_operation_ledger")
public class ComplianceOperationLedger extends BaseEntity {
    private String tenantId;
    private String operationId;
    private String operationType;
    private String idempotencyKey;
    private String requestHash;
    private String state;
    private Integer retryableFlag;
    private Integer attemptCount;
    private LocalDateTime startedAt;
    private LocalDateTime leaseUntil;
    private LocalDateTime finishedAt;
    private String resultReferenceType;
    private Long resultReferenceId;
    private String resultReferenceVersion;
    private String resultSummaryCanonical;
    private Integer resultHttpStatus;
    private String resultHash;
    private String failureCode;
    private String correlationId;
    private LocalDateTime expiresAt;

    @Version
    private Integer version;
}
