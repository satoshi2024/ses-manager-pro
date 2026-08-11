package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_compliance_mapping_status_event")
public class ComplianceMappingStatusEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long mappingId;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyHash;
    private String beforeStatus;
    private String afterStatus;
    private Long actorId;
    private String actorDisplayNameSnapshot;
    private String actorRoleSnapshot;
    private LocalDateTime occurredAt;
    private Integer expectedVersion;
    private String gateSnapshotHash;
    private String operationId;
    private String correlationId;
    private String reason;
    private LocalDateTime createdAt;
}
