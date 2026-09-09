package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** tenant ownershipを一意に復元できない行の修復監査記録。 */
@Data
@TableName("nf02_nf03_ownership_repair_queue")
public class OwnershipRepairQueue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String entityType;
    private Long entityId;
    private String reason;
    private LocalDateTime createdAt;
    private String status;
    private Long assigneeUserId;
    private String repairTenantId;
    private String resolutionReason;
    private String evidence;
    private LocalDateTime resolvedAt;
    private Long resolvedBy;
    private LocalDateTime lastCheckedAt;
    private Integer version;
    private String claimToken;
    private Long claimedBy;
    private LocalDateTime claimedAt;
    private Long incidentId;
    private String actorTenantId;
    private String evidenceHash;
    private Long approverId;
}
