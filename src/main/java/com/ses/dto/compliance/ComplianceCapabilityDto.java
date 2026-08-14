package com.ses.dto.compliance;

import lombok.Data;

/**
 * R23-P1-01 §5: server計算capability。JS role判定をauthorizationに使わない（§5）。
 * 各actionの可否をサーバー側で決定し、UI表示にのみ使用する。
 */
@Data
public class ComplianceCapabilityDto {
    private boolean canManageMapping;
    private boolean canManageReviewerType;
    private boolean canManagePolicy;
    private boolean canManageAssignment;
    private boolean canApprove;
    private boolean canManageExternalReview;
    private boolean canVerify;
    private boolean canManageActive;
    private boolean canViewEventHistory;
}
