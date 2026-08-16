package com.ses.dto.compliance;

import com.ses.entity.ComplianceExternalReviewerType;

/**
 * R23-P1-01 §5: typed response DTO（allow-list）for external reviewer type。
 * entityをAPI契約にせず、公開フィールドを限定する。
 */
public class ComplianceReviewerTypeDto {
    private Long id;
    private String tenantId;
    private String typeCode;
    private String displayName;
    private String description;
    private String credentialLabel;
    private boolean credentialRequired;
    private boolean enabled;
    private Integer sortOrder;

    public static ComplianceReviewerTypeDto fromEntity(ComplianceExternalReviewerType t) {
        ComplianceReviewerTypeDto dto = new ComplianceReviewerTypeDto();
        dto.id = t.getId();
        dto.tenantId = t.getTenantId();
        dto.typeCode = t.getTypeCode();
        dto.displayName = t.getDisplayName();
        dto.description = t.getDescription();
        dto.credentialLabel = t.getCredentialLabel();
        dto.credentialRequired = Integer.valueOf(1).equals(t.getCredentialRequired());
        dto.enabled = Integer.valueOf(1).equals(t.getEnabled());
        dto.sortOrder = t.getSortOrder();
        return dto;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getCredentialLabel() {
        return credentialLabel;
    }

    public boolean isCredentialRequired() {
        return credentialRequired;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
