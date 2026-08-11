package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_compliance_operation_ledger")
public class ComplianceOperationLedger {
    @TableId(type = IdType.AUTO)
    private Long id;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField("deleted_flag")
    private Integer deletedFlag;

    // operation ledgerはBaseEntityを継承しない。論理削除APIを公開せず、DB triggerで0固定する。
    private Integer version;
}
