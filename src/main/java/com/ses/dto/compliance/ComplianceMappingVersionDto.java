package com.ses.dto.compliance;

import com.ses.entity.ComplianceMappingVersion;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * R23-P1-01 §5: typed response DTO for compliance mapping version。
 * entityをAPI契約にせず、allow-listで公開フィールドを限定する（§5 typed DTO・Map/entity API置換）。
 */
public class ComplianceMappingVersionDto {
    private Long id;
    private String tenantId;
    private String mappingCode;
    private String mappingVersion;
    private String mappingHash;
    private String reviewPolicyHash;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String status;
    private Integer activeSlot;
    private Integer futureSlot;
    private LocalDateTime activatedAt;
    private Long activatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ComplianceMappingVersionDto fromEntity(ComplianceMappingVersion v) {
        ComplianceMappingVersionDto dto = new ComplianceMappingVersionDto();
        dto.id = v.getId();
        dto.tenantId = v.getTenantId();
        dto.mappingCode = v.getMappingCode();
        dto.mappingVersion = v.getMappingVersion();
        dto.mappingHash = v.getMappingHash();
        dto.reviewPolicyHash = v.getReviewPolicyHash();
        dto.effectiveFrom = v.getEffectiveFrom();
        dto.effectiveTo = v.getEffectiveTo();
        dto.status = v.getStatus();
        dto.activeSlot = v.getActiveSlot();
        dto.futureSlot = v.getFutureSlot();
        dto.activatedAt = v.getActivatedAt();
        dto.activatedBy = v.getActivatedBy();
        dto.createdAt = v.getCreatedAt();
        dto.updatedAt = v.getUpdatedAt();
        return dto;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getMappingCode() {
        return mappingCode;
    }

    public String getMappingVersion() {
        return mappingVersion;
    }

    public String getMappingHash() {
        return mappingHash;
    }

    public String getReviewPolicyHash() {
        return reviewPolicyHash;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getStatus() {
        return status;
    }

    public Integer getActiveSlot() {
        return activeSlot;
    }

    public Integer getFutureSlot() {
        return futureSlot;
    }

    public LocalDateTime getActivatedAt() {
        return activatedAt;
    }

    public Long getActivatedBy() {
        return activatedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
