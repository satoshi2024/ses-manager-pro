package com.ses.dto.compliance;

import com.ses.entity.ComplianceMappingApprovalEvent;

import java.time.LocalDateTime;

/**
 * R23-P1-01 P1-1: typed response DTO（allow-list）for internal approval event。
 * entityをAPI契約にしない。
 */
public class ComplianceApprovalEventDto {
    private Long id;
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
    private LocalDateTime occurredAt;
    private String reason;
    private Long evidenceDocumentId;
    private Long evidenceDocumentVersionId;
    private String evidenceDocumentVersion;
    private String evidenceDocumentHash;
    private String evidenceScanStatus;

    public static ComplianceApprovalEventDto fromEntity(ComplianceMappingApprovalEvent e) {
        ComplianceApprovalEventDto dto = new ComplianceApprovalEventDto();
        dto.id = e.getId();
        dto.mappingId = e.getMappingId();
        dto.mappingVersion = e.getMappingVersion();
        dto.mappingHash = e.getMappingHash();
        dto.reviewPolicyHash = e.getReviewPolicyHash();
        dto.assignmentId = e.getAssignmentId();
        dto.workplaceIdSnapshot = e.getWorkplaceIdSnapshot();
        dto.actorId = e.getActorId();
        dto.actorDisplayNameSnapshot = e.getActorDisplayNameSnapshot();
        dto.actorRoleSnapshot = e.getActorRoleSnapshot();
        dto.action = e.getAction();
        dto.occurredAt = e.getOccurredAt();
        dto.reason = e.getReason();
        dto.evidenceDocumentId = e.getEvidenceDocumentId();
        dto.evidenceDocumentVersionId = e.getEvidenceDocumentVersionId();
        dto.evidenceDocumentVersion = e.getEvidenceDocumentVersion();
        dto.evidenceDocumentHash = e.getEvidenceDocumentHash();
        dto.evidenceScanStatus = e.getEvidenceScanStatus();
        return dto;
    }

    public Long getId() {
        return id;
    }

    public Long getMappingId() {
        return mappingId;
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

    public Long getAssignmentId() {
        return assignmentId;
    }

    public Long getWorkplaceIdSnapshot() {
        return workplaceIdSnapshot;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorDisplayNameSnapshot() {
        return actorDisplayNameSnapshot;
    }

    public String getActorRoleSnapshot() {
        return actorRoleSnapshot;
    }

    public String getAction() {
        return action;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getReason() {
        return reason;
    }

    public Long getEvidenceDocumentId() {
        return evidenceDocumentId;
    }

    public Long getEvidenceDocumentVersionId() {
        return evidenceDocumentVersionId;
    }

    public String getEvidenceDocumentVersion() {
        return evidenceDocumentVersion;
    }

    public String getEvidenceDocumentHash() {
        return evidenceDocumentHash;
    }

    public String getEvidenceScanStatus() {
        return evidenceScanStatus;
    }
}
