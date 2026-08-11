package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_compliance_mapping_approval_event")
public class ComplianceMappingApprovalEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long mappingId;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyHash;
    private Long assignmentId;
    private Long workplaceIdSnapshot;
    private Long actorId;
    private String actorDisplayNameSnapshot;
    private String actorRoleSnapshot;
    private String action;
    private String eventChainId;
    private Long targetEventId;
    private Long supersedesEventId;
    private LocalDateTime occurredAt;
    private String reason;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String evidenceDocumentVersion;
    private String evidenceDocumentHash;
    private String operationId;
    private String correlationId;
    private String idempotencyKey;
    private LocalDateTime createdAt;
}
