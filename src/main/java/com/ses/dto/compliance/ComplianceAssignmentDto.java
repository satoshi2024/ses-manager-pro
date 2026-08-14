package com.ses.dto.compliance;

import com.ses.entity.ComplianceResponsibleAssignment;

import java.time.LocalDateTime;

/**
 * R23-P1-01 P1-1: typed response DTO（allow-list）for responsible assignment。
 * entityをAPI契約にしない。
 */
public class ComplianceAssignmentDto {
    private Long id;
    private String tenantId;
    private Long workplaceId;
    private Long userId;
    private String roleCode;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Integer activeSlot;
    private Long assignedBy;
    private Long endedBy;
    private String endReason;

    public static ComplianceAssignmentDto fromEntity(ComplianceResponsibleAssignment a) {
        ComplianceAssignmentDto dto = new ComplianceAssignmentDto();
        dto.id = a.getId();
        dto.tenantId = a.getTenantId();
        dto.workplaceId = a.getWorkplaceId();
        dto.userId = a.getUserId();
        dto.roleCode = a.getRoleCode();
        dto.effectiveFrom = a.getEffectiveFrom();
        dto.effectiveTo = a.getEffectiveTo();
        dto.activeSlot = a.getActiveSlot();
        dto.assignedBy = a.getAssignedBy();
        dto.endedBy = a.getEndedBy();
        dto.endReason = a.getEndReason();
        return dto;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getWorkplaceId() {
        return workplaceId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public Integer getActiveSlot() {
        return activeSlot;
    }

    public Long getAssignedBy() {
        return assignedBy;
    }

    public Long getEndedBy() {
        return endedBy;
    }

    public String getEndReason() {
        return endReason;
    }
}
