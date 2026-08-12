package com.ses.service;

import com.ses.entity.ComplianceExternalReviewerType;
import com.ses.entity.ComplianceResponsibleAssignment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * G2 gate admin service（Phase A step 3）。
 *  - reviewer type管理（m_compliance_external_reviewer_type: 資格label・必須・enabled）
 *  - COMPLIANCE_RESPONSIBLE assignment（t_compliance_responsible_assignment: 半開区間・active_slot単一）
 */
public interface ComplianceGateAdminService {

    List<ComplianceExternalReviewerType> listReviewerTypes();

    ComplianceExternalReviewerType createReviewerType(String typeCode, String displayName, String description,
                                                      String credentialLabel, boolean credentialRequired);

    ComplianceExternalReviewerType updateReviewerType(Long typeId, String displayName, String description,
                                                      String credentialLabel, boolean credentialRequired);

    ComplianceExternalReviewerType setReviewerTypeEnabled(Long typeId, boolean enabled);

    /** 現行open（active_slot=1）を終了し、新assignmentを開始する（同一workplaceのactive_slotは常に1つ）。 */
    ComplianceResponsibleAssignment createAssignment(Long workplaceId, Long userId, LocalDateTime effectiveFrom);

    ComplianceResponsibleAssignment endAssignment(Long assignmentId, String reason);
}
