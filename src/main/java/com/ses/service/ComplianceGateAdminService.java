package com.ses.service;

import com.ses.entity.ComplianceExternalReviewerType;
import com.ses.entity.ComplianceMappingReviewRequirementGroup;
import com.ses.entity.ComplianceResponsibleAssignment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * G2 gate admin service（Phase A step 3）。
 *  - reviewer type管理（m_compliance_external_reviewer_type: 資格label・必須・enabled）
 *  - COMPLIANCE_RESPONSIBLE assignment（t_compliance_responsible_assignment: 半開区間・active_slot単一）
 *  - review requirement group/type（m_compliance_mapping_review_requirement_group/_type: mapping別policy）
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

    List<ComplianceMappingReviewRequirementGroup> listRequirementGroups(Long mappingId);

    /** mappingにrequirement groupを追加し、policy変更をmapping versionのreview_policy_hashへ反映する。 */
    ComplianceMappingReviewRequirementGroup createRequirementGroup(Long mappingId, String groupCode,
                                                                   String displayName, int minimumDistinctReviewers);

    /** groupへreviewer typeを追加し（typeのcode/name/credentialをsnapshot）、policy hashを反映する。 */
    com.ses.entity.ComplianceMappingReviewRequirementType addRequirementType(Long groupId, Long reviewerTypeId);

    /** 外部資格保有者のReviewイベントをAES-256-GCM暗号化（§6.5）で記録する。 */
    com.ses.entity.ComplianceExternalReviewEvent recordExternalReview(Long mappingId, Long requirementGroupId, Long reviewerTypeId,
                                                                       String reviewerName, String organization, String credentialRaw,
                                                                       String action, LocalDateTime reviewedAt,
                                                                       LocalDateTime validUntil, Long evidenceDocumentId, String reason,
                                                                       Long targetEventId);

    List<com.ses.entity.ComplianceExternalReviewEvent> listExternalReviews(Long mappingId);

    /** R23-P1-01 §5: external reviewer subject一覧（person-stable正本・fingerprint masked表現）。 */
    List<com.ses.entity.ComplianceExternalReviewerSubject> listSubjects();

    /** R23-P1-01 §5: 指定mappingに属するverification event一覧（external review経由）。 */
    List<com.ses.entity.ComplianceExternalReviewerVerificationEvent> listVerificationsByMapping(Long mappingId);
}
