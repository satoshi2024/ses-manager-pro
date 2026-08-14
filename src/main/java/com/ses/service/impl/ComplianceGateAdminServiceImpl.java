package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.ComplianceExternalReviewerType;
import com.ses.entity.ComplianceResponsibleAssignment;
import com.ses.mapper.ComplianceExternalReviewerTypeMapper;
import com.ses.mapper.ComplianceResponsibleAssignmentMapper;
import com.ses.service.ComplianceGateAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * G2 gate admin service（Phase A step 3）。
 *  - reviewer type: tenant単位のtype_code一意・credential label/required・enabled
 *  - assignment: 半開区間（effective_from <= now < effective_to）・同一workplaceのopen assignmentは
 *    active_slot=1で常に1つ（新規create時に既存openを終了し、CASで競合制御）
 */
@Service
@RequiredArgsConstructor
public class ComplianceGateAdminServiceImpl implements ComplianceGateAdminService {

    private final ComplianceExternalReviewerTypeMapper reviewerTypeMapper;
    private final ComplianceResponsibleAssignmentMapper assignmentMapper;
    private final com.ses.mapper.ComplianceMappingReviewRequirementGroupMapper requirementGroupMapper;
    private final com.ses.mapper.ComplianceMappingReviewRequirementTypeMapper requirementTypeMapper;
    private final com.ses.mapper.ComplianceMappingVersionMapper versionMapper;
    private final com.ses.mapper.WorkplaceMapper workplaceMapper;
    private final com.ses.service.compliance.ComplianceMappingCanonicalizer canonicalizer;
    private final com.ses.mapper.ComplianceExternalReviewEventMapper externalReviewEventMapper;
    private final com.ses.mapper.ComplianceExternalReviewerSubjectMapper reviewerSubjectMapper;
    private final com.ses.mapper.ComplianceExternalReviewerVerificationEventMapper verificationEventMapper;
    private final com.ses.mapper.ComplianceVerificationSourceMapper verificationSourceMapper;
    private final com.ses.mapper.ComplianceVerificationMethodMapper verificationMethodMapper;
    private final com.ses.mapper.ComplianceReviewerQualificationMapper qualificationMapper;
    private final com.ses.mapper.DocumentVersionMapper documentVersionMapper;
    private final com.ses.mapper.ComplianceMappingSourceMapper complianceSourceMapper;
    private final com.ses.mapper.ComplianceMappingApprovalEventMapper complianceApprovalEventMapper;
    private final com.ses.mapper.ComplianceExternalReviewAdoptionEventMapper complianceAdoptionEventMapper;
    private final com.ses.service.compliance.ComplianceGateCredentialCryptoService credentialCryptoService;
    private final com.ses.service.compliance.ComplianceGateCredentialKeyProvider keyProvider;
    private final com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService;
    private final com.ses.service.compliance.ComplianceReviewerFingerprintKeyProvider fingerprintKeyProvider;
    private final com.ses.service.compliance.ComplianceTenantResolver tenantResolver;

    private String tenantId() {
        return tenantResolver.currentTenantId();
    }

    @Override
    public List<ComplianceExternalReviewerType> listReviewerTypes() {
        return reviewerTypeMapper.selectList(new LambdaQueryWrapper<ComplianceExternalReviewerType>()
                .eq(ComplianceExternalReviewerType::getTenantId, tenantId())
                .orderByAsc(ComplianceExternalReviewerType::getSortOrder));
    }

    @Override
    @Transactional
    public ComplianceExternalReviewerType createReviewerType(String typeCode, String displayName, String description,
                                                             String credentialLabel, boolean credentialRequired) {
        if (!StringUtils.hasText(typeCode) || !StringUtils.hasText(displayName)) {
            throw BusinessException.of(400, "compliance.gate.invalidReviewerType");
        }
        Long existing = reviewerTypeMapper.selectCount(new LambdaQueryWrapper<ComplianceExternalReviewerType>()
                .eq(ComplianceExternalReviewerType::getTenantId, tenantId())
                .eq(ComplianceExternalReviewerType::getTypeCode, typeCode));
        if (existing != null && existing > 0) {
            throw BusinessException.of(400, "compliance.gate.duplicateReviewerType");
        }
        ComplianceExternalReviewerType type = new ComplianceExternalReviewerType();
        type.setTenantId(tenantId());
        type.setTypeCode(typeCode);
        type.setDisplayName(displayName);
        type.setDescription(description);
        type.setCredentialLabel(credentialLabel);
        type.setCredentialRequired(credentialRequired ? 1 : 0);
        type.setEnabled(1);
        type.setSortOrder(0);
        type.setCreatedBy(SecurityUtils.currentUserId());
        reviewerTypeMapper.insert(type);
        return type;
    }

    @Override
    @Transactional
    public ComplianceExternalReviewerType updateReviewerType(Long typeId, String displayName, String description,
                                                             String credentialLabel, boolean credentialRequired) {
        ComplianceExternalReviewerType type = reviewerTypeMapper.selectById(typeId);
        if (type == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        type.setDisplayName(displayName);
        type.setDescription(description);
        type.setCredentialLabel(credentialLabel);
        type.setCredentialRequired(credentialRequired ? 1 : 0);
        type.setUpdatedBy(SecurityUtils.currentUserId());
        int rows = reviewerTypeMapper.updateById(type);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return type;
    }

    @Override
    @Transactional
    public ComplianceExternalReviewerType setReviewerTypeEnabled(Long typeId, boolean enabled) {
        ComplianceExternalReviewerType type = reviewerTypeMapper.selectById(typeId);
        if (type == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        type.setEnabled(enabled ? 1 : 0);
        type.setUpdatedBy(SecurityUtils.currentUserId());
        int rows = reviewerTypeMapper.updateById(type);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return type;
    }

    @Override
    @Transactional
    public ComplianceResponsibleAssignment createAssignment(Long workplaceId, Long userId, LocalDateTime effectiveFrom) {
        if (workplaceId == null || userId == null || effectiveFrom == null) {
            throw BusinessException.of(400, "compliance.gate.invalidAssignment");
        }
        LocalDateTime now = LocalDateTime.now();
        if (effectiveFrom.isAfter(now)) {
            throw BusinessException.of(400, "compliance.gate.invalidAssignment");
        }
        // P6・§2.2（G2-ASG）: anchor lockで同一workplaceの並行createAssignmentを直列化する
        Long locked = workplaceMapper.selectIdForUpdate(tenantId(), workplaceId);
        if (locked == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // P6・§2.2（G2-ASG）: 有限期間assignmentとのoverlap拒否。
        // 新開始時点（effectiveFrom）で既に有効区間が終了していない有限期間行（effective_to > effectiveFrom）が
        // あれば、同一workplaceに有効1件の契約（asOfで有効は常に1件）に違反するため拒否する。
        // 現行open（active_slot=1・effective_to NULL）は交代として終了されるため対象外。
        Long overlapping = assignmentMapper.selectCount(
                new LambdaQueryWrapper<ComplianceResponsibleAssignment>()
                        .eq(ComplianceResponsibleAssignment::getTenantId, tenantId())
                        .eq(ComplianceResponsibleAssignment::getWorkplaceId, workplaceId)
                        .isNotNull(ComplianceResponsibleAssignment::getEffectiveTo)
                        .gt(ComplianceResponsibleAssignment::getEffectiveTo, effectiveFrom));
        if (overlapping != null && overlapping > 0) {
            throw BusinessException.of(409, "compliance.gate.assignmentOverlap");
        }
        // 既存open（active_slot=1・effective_to NULL）を終了する
        List<ComplianceResponsibleAssignment> open = assignmentMapper.selectList(
                new LambdaQueryWrapper<ComplianceResponsibleAssignment>()
                        .eq(ComplianceResponsibleAssignment::getTenantId, tenantId())
                        .eq(ComplianceResponsibleAssignment::getWorkplaceId, workplaceId)
                        .eq(ComplianceResponsibleAssignment::getActiveSlot, 1));
        for (ComplianceResponsibleAssignment current : open) {
            // endAssignmentと同じtick問題（TIMESTAMP(6)丸めで期間CHECK違反）へのガード。
            // DB保存はµs精度のため、nowをµsへtruncateしてから比較・設定しないと、
            // nowがeffective_fromより後でもµs丸めで同値になりchk_g2_assignment_period違反になり得る。
            LocalDateTime endAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (current.getEffectiveFrom() != null && !endAt.isAfter(current.getEffectiveFrom())) {
                endAt = current.getEffectiveFrom().plusNanos(1000);
            }
            current.setEffectiveTo(endAt);
            current.setActiveSlot(null);
            current.setEndedBy(SecurityUtils.currentUserId());
            current.setEndReason("交代（新assignment開始）");
            int rows = assignmentMapper.updateById(current);
            if (rows == 0) {
                throw BusinessException.of(409, "contract.compliance.versionConflict");
            }
        }
        ComplianceResponsibleAssignment assignment = new ComplianceResponsibleAssignment();
        assignment.setTenantId(tenantId());
        assignment.setWorkplaceId(workplaceId);
        assignment.setUserId(userId);
        assignment.setRoleCode("COMPLIANCE_RESPONSIBLE");
        assignment.setEffectiveFrom(effectiveFrom);
        assignment.setActiveSlot(1);
        assignment.setAssignedBy(SecurityUtils.currentUserId());
        assignmentMapper.insert(assignment);
        return assignment;
    }

    @Override
    @Transactional
    public ComplianceResponsibleAssignment endAssignment(Long assignmentId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw BusinessException.of(400, "compliance.gate.endReasonRequired");
        }
        ComplianceResponsibleAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!Integer.valueOf(1).equals(assignment.getActiveSlot())) {
            throw BusinessException.of(400, "compliance.gate.assignmentNotOpen");
        }
        // Windows等の粗い時刻粒度でnow()がeffective_fromと同一tickになると、
        // H2/MySQLのTIMESTAMP(6)丸めと合わせて期間CHECK（effective_from < effective_to）違反になり得るため、
        // effective_toは常にµs丸め後もeffective_fromより後になるようガードする（TIMESTAMP(6)はµs精度のため1µs余裕）。
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (assignment.getEffectiveFrom() != null && !now.isAfter(assignment.getEffectiveFrom())) {
            now = assignment.getEffectiveFrom().plusNanos(1000);
        }
        assignment.setEffectiveTo(now);
        assignment.setActiveSlot(null);
        assignment.setEndedBy(SecurityUtils.currentUserId());
        assignment.setEndReason(reason);
        int rows = assignmentMapper.updateById(assignment);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return assignment;
    }

    @Override
    public List<com.ses.entity.ComplianceMappingReviewRequirementGroup> listRequirementGroups(Long mappingId) {
        return requirementGroupMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, tenantId())
                .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, mappingId)
                .orderByAsc(com.ses.entity.ComplianceMappingReviewRequirementGroup::getRequirementGroupCode));
    }

    @Override
    @Transactional
    public com.ses.entity.ComplianceMappingReviewRequirementGroup createRequirementGroup(Long mappingId, String groupCode,
                                                                                         String displayName,
                                                                                         int minimumDistinctReviewers) {
        if (!StringUtils.hasText(groupCode) || !StringUtils.hasText(displayName) || minimumDistinctReviewers < 1) {
            throw BusinessException.of(400, "compliance.gate.invalidRequirementGroup");
        }
        com.ses.entity.ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!ComplianceMappingServiceImpl.STATUS_DRAFT.equals(version.getStatus())) {
            throw BusinessException.of(400, "compliance.gate.mappingFrozen");
        }
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                new com.ses.entity.ComplianceMappingReviewRequirementGroup();
        group.setTenantId(tenantId());
        group.setMappingId(mappingId);
        group.setRequirementGroupCode(groupCode);
        group.setDisplayName(displayName);
        group.setMinimumDistinctReviewers(minimumDistinctReviewers);
        group.setSortOrder(0);
        group.setCreatedBy(SecurityUtils.currentUserId());
        requirementGroupMapper.insert(group);
        refreshPolicyHash(mappingId);
        return group;
    }

    @Override
    @Transactional
    public com.ses.entity.ComplianceMappingReviewRequirementType addRequirementType(Long groupId, Long reviewerTypeId) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                requirementGroupMapper.selectById(groupId);
        if (group == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        com.ses.entity.ComplianceMappingVersion version = versionMapper.selectById(group.getMappingId());
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!ComplianceMappingServiceImpl.STATUS_DRAFT.equals(version.getStatus())) {
            throw BusinessException.of(400, "compliance.gate.mappingFrozen");
        }
        com.ses.entity.ComplianceExternalReviewerType type = reviewerTypeMapper.selectById(reviewerTypeId);
        if (type == null || Integer.valueOf(0).equals(type.getEnabled())) {
            throw BusinessException.of(400, "compliance.gate.invalidRequirementType");
        }
        com.ses.entity.ComplianceMappingReviewRequirementType requirementType =
                new com.ses.entity.ComplianceMappingReviewRequirementType();
        requirementType.setTenantId(tenantId());
        requirementType.setRequirementGroupId(group.getId());
        requirementType.setReviewerTypeId(type.getId());
        requirementType.setReviewerTypeCodeSnapshot(type.getTypeCode());
        requirementType.setReviewerTypeNameSnapshot(type.getDisplayName());
        requirementType.setCredentialLabelSnapshot(type.getCredentialLabel());
        requirementType.setCredentialRequiredSnapshot(type.getCredentialRequired());
        requirementType.setCreatedBy(SecurityUtils.currentUserId());
        requirementTypeMapper.insert(requirementType);
        refreshPolicyHash(group.getMappingId());
        return requirementType;
    }

    /** policy（group/type）変更をmapping versionのreview_policy_hashへ反映する（DRAFTのみ）。 */
    private void refreshPolicyHash(Long mappingId) {
        com.ses.entity.ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null || !ComplianceMappingServiceImpl.STATUS_DRAFT.equals(version.getStatus())) {
            return;
        }
        List<com.ses.entity.ComplianceMappingReviewRequirementGroup> groups =
                requirementGroupMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, tenantId())
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, mappingId));
        List<Long> groupIds = groups.stream().map(com.ses.entity.ComplianceMappingReviewRequirementGroup::getId).toList();
        List<com.ses.entity.ComplianceMappingReviewRequirementType> types = groupIds.isEmpty() ? List.of() :
                requirementTypeMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                        .in(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds));
        version.setReviewPolicyHash(canonicalizer.computeReviewPolicyHash(groups, types));
        versionMapper.updateById(version);
    }


    @Override
    @Transactional
    public com.ses.entity.ComplianceExternalReviewEvent recordExternalReview(Long mappingId, Long requirementGroupId, Long reviewerTypeId,
                                                                               String reviewerName, String organization, String credentialRaw,
                                                                               String action, LocalDateTime reviewedAt,
                                                                               LocalDateTime validUntil, Long evidenceDocumentId, String reason,
                                                                               Long targetEventId) {
        if (!StringUtils.hasText(reviewerName) || !StringUtils.hasText(organization)) {
            throw BusinessException.of(400, "compliance.gate.invalidExternalReview");
        }
        // K1: 新規write pathはSUBMITTEDのみ。旧APPROVED/REJECTED/REVOKED直接記録は廃止（legacy rowは新gate不採用）。
        // adoption（APPROVED/REJECTED/REVOKED）はt_compliance_external_review_adoption_eventへ別途記録される。
        String normalizedAction = StringUtils.hasText(action) ? action.trim().toUpperCase() : "SUBMITTED";
        if (!"SUBMITTED".equals(normalizedAction)) {
            throw BusinessException.of(400, "compliance.gate.invalidAction");
        }

        com.ses.entity.ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        com.ses.entity.ComplianceMappingReviewRequirementGroup group = requirementGroupMapper.selectById(requirementGroupId);
        if (group == null || !mappingId.equals(group.getMappingId())) {
            throw BusinessException.of(400, "compliance.gate.invalidRequirementGroup");
        }
        com.ses.entity.ComplianceExternalReviewerType type = reviewerTypeMapper.selectById(reviewerTypeId);
        if (type == null || Integer.valueOf(0).equals(type.getEnabled())) {
            throw BusinessException.of(400, "compliance.gate.invalidReviewerType");
        }

        // Verify reviewerType is assigned to requirementGroupId
        Long reqTypeCount = requirementTypeMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, requirementGroupId)
                .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getReviewerTypeId, reviewerTypeId));
        if (reqTypeCount == null || reqTypeCount == 0) {
            throw BusinessException.of(409, "compliance.gate.invalidRequirementType");
        }

        // §4-4: credential必須判定はcurrent reviewer type masterではなくfreeze済みsnapshotを使用する
        // （mapping policyはmapping versionへfreezeされ、master変更の影響を受けない）。
        List<com.ses.entity.ComplianceMappingReviewRequirementType> frozenTypes = requirementTypeMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, requirementGroupId)
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getReviewerTypeId, reviewerTypeId));
        boolean credentialRequired = !frozenTypes.isEmpty()
                && Integer.valueOf(1).equals(frozenTypes.get(0).getCredentialRequiredSnapshot());
        boolean hasCredential = StringUtils.hasText(credentialRaw);
        if (credentialRequired && !hasCredential) {
            throw BusinessException.of(400, "compliance.gate.credentialRequired");
        }

        String opId = java.util.UUID.randomUUID().toString();
        String encrypted = null;
        String keyVer = null;
        String cipherFormat = null;
        String masked = null;

        if (hasCredential) {
            String rawTrim = credentialRaw.trim();
            encrypted = credentialCryptoService.encrypt(tenantId(), mappingId, version.getMappingVersion(), opId, rawTrim);
            keyVer = keyProvider.getCurrentKeyVersion();
            cipherFormat = com.ses.service.compliance.ComplianceGateCredentialCryptoServiceImpl.CIPHER_FORMAT_CGC1;
            masked = rawTrim.length() > 4 ? "****" + rawTrim.substring(rawTrim.length() - 4) : "VALIDATED";
        }

        String identityHash = credentialCryptoService.computeIdentityHash(type.getTypeCode(), credentialRaw, organization, reviewerName);

        String reviewChainId = null;
        Long supersedesId = null;
        Long targetId = null;

        if (targetEventId != null) {
            com.ses.entity.ComplianceExternalReviewEvent targetEvent = externalReviewEventMapper.selectByTenantAndId(tenantId(), targetEventId);
            if (targetEvent != null && mappingId.equals(targetEvent.getMappingId()) && requirementGroupId.equals(targetEvent.getRequirementGroupId())) {
                reviewChainId = targetEvent.getReviewChainId();
                supersedesId = targetEvent.getId();
            }
        }
        if (!StringUtils.hasText(reviewChainId)) {
            reviewChainId = java.util.UUID.randomUUID().toString();
        }

        com.ses.entity.ComplianceExternalReviewEvent event = new com.ses.entity.ComplianceExternalReviewEvent();
        event.setTenantId(tenantId());
        event.setMappingId(mappingId);
        event.setMappingVersion(version.getMappingVersion());
        event.setMappingHash(version.getMappingHash());
        event.setReviewPolicyHash(version.getReviewPolicyHash());
        event.setRequirementGroupId(group.getId());
        event.setRequirementGroupCodeSnapshot(group.getRequirementGroupCode());
        event.setReviewerTypeId(type.getId());
        event.setReviewerTypeCodeSnapshot(type.getTypeCode());
        event.setReviewerTypeNameSnapshot(type.getDisplayName());
        event.setReviewerNameSnapshot(reviewerName);
        event.setOrganizationSnapshot(organization);
        event.setCredentialSnapshotEncrypted(encrypted);
        event.setCredentialKeyVersion(keyVer);
        event.setCredentialCipherFormat(cipherFormat);
        event.setCredentialMaskedSnapshot(masked);
        event.setReviewerIdentityHash(identityHash);
        event.setAction(normalizedAction);
        event.setReviewChainId(reviewChainId);
        event.setTargetEventId(targetId);
        event.setSupersedesEventId(supersedesId);
        event.setReviewedAt(reviewedAt != null ? reviewedAt : LocalDateTime.now());
        event.setValidUntil(validUntil);
        event.setRecordedAt(LocalDateTime.now());
        event.setEvidenceDocumentId(evidenceDocumentId);
        event.setRecordedBy(SecurityUtils.currentUserId());
        event.setOperationId(opId);
        event.setCorrelationId(java.util.UUID.randomUUID().toString());

        // E-4: 決定論的idempotencyKey（requestパラメータのみから導出・reviewedAt等のサーバー既定時刻を含めない）。
        // 同内容の再送は同一キーとなり、UNIQUE(tenant_id, idempotency_key)の二重防御で重複挿入を409で拒否する（approvalと同契約）。
        String idemKey = sha256Hex("EXT_REV:default:" + mappingId + ":" + group.getId() + ":" + identityHash + ":" + normalizedAction);
        event.setIdempotencyKey(idemKey);

        if (externalReviewEventMapper != null) {
            try {
                externalReviewEventMapper.insertEvent(event);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw BusinessException.of(409, "contract.compliance.versionConflict");
            }
        }
        return event;
    }

    @Override
    public List<com.ses.entity.ComplianceExternalReviewEvent> listExternalReviews(Long mappingId) {
        if (externalReviewEventMapper == null) {
            return List.of();
        }
        return externalReviewEventMapper.selectByMapping(tenantId(), mappingId);
    }

    @Override
    public List<com.ses.entity.ComplianceExternalReviewerSubject> listSubjects() {
        return reviewerSubjectMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceExternalReviewerSubject>()
                .eq(com.ses.entity.ComplianceExternalReviewerSubject::getTenantId, tenantId())
                .orderByAsc(com.ses.entity.ComplianceExternalReviewerSubject::getId));
    }

    @Override
    public List<com.ses.entity.ComplianceExternalReviewerVerificationEvent> listVerificationsByMapping(Long mappingId) {
        if (mappingId == null) {
            return List.of();
        }
        // mappingに属するSUBMITTED review event配下のverification eventを結合で取得する
        List<com.ses.entity.ComplianceExternalReviewEvent> reviews =
                externalReviewEventMapper.selectByMapping(tenantId(), mappingId);
        if (reviews.isEmpty()) {
            return List.of();
        }
        java.util.List<com.ses.entity.ComplianceExternalReviewerVerificationEvent> result = new java.util.ArrayList<>();
        for (com.ses.entity.ComplianceExternalReviewEvent review : reviews) {
            result.addAll(verificationEventMapper.selectBySubmittedReview(tenantId(), review.getId()));
        }
        result.sort(java.util.Comparator.comparing(com.ses.entity.ComplianceExternalReviewerVerificationEvent::getCreatedAt));
        return result;
    }

    // ===== R23-P1-01 §3.8/§8 dynamic policy（V102_3・P0-3） =====

    @Override
    @Transactional
    public ComplianceExternalReviewerType updateReviewerTypeDynamic(Long typeId, Integer qualificationVerificationRequired,
                                                                    Integer activeStatusVerificationRequired,
                                                                    Long verificationSourceId, Long verificationMethodId,
                                                                    Integer maxAgeDays, java.time.LocalDate effectiveFrom,
                                                                    java.time.LocalDate effectiveTo) {
        ComplianceExternalReviewerType type = reviewerTypeMapper.selectById(typeId);
        if (type == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // §8: flagsは管理者の明示選択（true/false）を必須。NULL=UNCONFIGUREDはAPI経由では設定不可。
        if (qualificationVerificationRequired == null || activeStatusVerificationRequired == null) {
            throw BusinessException.of(400, "compliance.gate.requiredFlagsMissing");
        }
        if ((qualificationVerificationRequired != 0 && qualificationVerificationRequired != 1)
                || (activeStatusVerificationRequired != 0 && activeStatusVerificationRequired != 1)) {
            throw BusinessException.of(400, "compliance.gate.invalidReviewerType");
        }
        if (verificationSourceId != null && verificationSourceMapper.selectById(verificationSourceId) == null) {
            throw BusinessException.of(400, "compliance.gate.sourceNotFound");
        }
        if (verificationMethodId != null && verificationMethodMapper.selectById(verificationMethodId) == null) {
            throw BusinessException.of(400, "compliance.gate.methodNotFound");
        }
        if (maxAgeDays != null && maxAgeDays < 1) {
            throw BusinessException.of(400, "compliance.gate.invalidMaxAge");
        }
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw BusinessException.of(400, "compliance.gate.invalidEffectivePeriod");
        }
        type.setQualificationVerificationRequired(qualificationVerificationRequired);
        type.setActiveStatusVerificationRequired(activeStatusVerificationRequired);
        type.setVerificationSourceId(verificationSourceId);
        type.setVerificationMethodId(verificationMethodId);
        type.setMaxAgeDays(maxAgeDays);
        type.setEffectiveFrom(effectiveFrom);
        type.setEffectiveTo(effectiveTo);
        type.setUpdatedBy(SecurityUtils.currentUserId());
        int rows = reviewerTypeMapper.updateById(type);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return type;
    }

    @Override
    public List<com.ses.entity.ComplianceVerificationSource> listVerificationSources() {
        return verificationSourceMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceVerificationSource>()
                .eq(com.ses.entity.ComplianceVerificationSource::getTenantId, tenantId())
                .orderByAsc(com.ses.entity.ComplianceVerificationSource::getSortOrder));
    }

    @Override
    @Transactional
    public com.ses.entity.ComplianceVerificationSource createVerificationSource(
            String sourceCode, String sourceName, String officialUrl, boolean enabled,
            java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo) {
        if (!StringUtils.hasText(sourceCode) || !StringUtils.hasText(sourceName)) {
            throw BusinessException.of(400, "compliance.gate.invalidSource");
        }
        Long existing = verificationSourceMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ComplianceVerificationSource>()
                .eq(com.ses.entity.ComplianceVerificationSource::getTenantId, tenantId())
                .eq(com.ses.entity.ComplianceVerificationSource::getSourceCode, sourceCode));
        if (existing != null && existing > 0) {
            throw BusinessException.of(400, "compliance.gate.duplicateSource");
        }
        com.ses.entity.ComplianceVerificationSource source = new com.ses.entity.ComplianceVerificationSource();
        source.setTenantId(tenantId());
        source.setSourceCode(sourceCode);
        source.setSourceName(sourceName);
        source.setOfficialUrl(officialUrl);
        source.setEnabled(enabled ? 1 : 0);
        source.setEffectiveFrom(effectiveFrom);
        source.setEffectiveTo(effectiveTo);
        source.setSortOrder(0);
        source.setCreatedBy(SecurityUtils.currentUserId());
        verificationSourceMapper.insert(source);
        return source;
    }

    @Override
    @Transactional
    public com.ses.entity.ComplianceVerificationSource updateVerificationSource(
            Long sourceId, String sourceName, String officialUrl, boolean enabled,
            java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo) {
        com.ses.entity.ComplianceVerificationSource source = verificationSourceMapper.selectById(sourceId);
        if (source == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!StringUtils.hasText(sourceName)) {
            throw BusinessException.of(400, "compliance.gate.invalidSource");
        }
        source.setSourceName(sourceName);
        source.setOfficialUrl(officialUrl);
        source.setEnabled(enabled ? 1 : 0);
        source.setEffectiveFrom(effectiveFrom);
        source.setEffectiveTo(effectiveTo);
        source.setUpdatedBy(SecurityUtils.currentUserId());
        int rows = verificationSourceMapper.updateById(source);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return source;
    }

    @Override
    public List<com.ses.entity.ComplianceVerificationMethod> listVerificationMethods() {
        return verificationMethodMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceVerificationMethod>()
                .eq(com.ses.entity.ComplianceVerificationMethod::getTenantId, tenantId())
                .orderByAsc(com.ses.entity.ComplianceVerificationMethod::getSortOrder));
    }

    @Override
    @Transactional
    public com.ses.entity.ComplianceVerificationMethod createVerificationMethod(
            String methodCode, String methodName, String description, boolean enabled,
            java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo) {
        if (!StringUtils.hasText(methodCode) || !StringUtils.hasText(methodName)) {
            throw BusinessException.of(400, "compliance.gate.invalidMethod");
        }
        Long existing = verificationMethodMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ComplianceVerificationMethod>()
                .eq(com.ses.entity.ComplianceVerificationMethod::getTenantId, tenantId())
                .eq(com.ses.entity.ComplianceVerificationMethod::getMethodCode, methodCode));
        if (existing != null && existing > 0) {
            throw BusinessException.of(400, "compliance.gate.duplicateMethod");
        }
        com.ses.entity.ComplianceVerificationMethod method = new com.ses.entity.ComplianceVerificationMethod();
        method.setTenantId(tenantId());
        method.setMethodCode(methodCode);
        method.setMethodName(methodName);
        method.setDescription(description);
        method.setEnabled(enabled ? 1 : 0);
        method.setEffectiveFrom(effectiveFrom);
        method.setEffectiveTo(effectiveTo);
        method.setSortOrder(0);
        method.setCreatedBy(SecurityUtils.currentUserId());
        verificationMethodMapper.insert(method);
        return method;
    }

    @Override
    @Transactional
    public com.ses.entity.ComplianceVerificationMethod updateVerificationMethod(
            Long methodId, String methodName, String description, boolean enabled,
            java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo) {
        com.ses.entity.ComplianceVerificationMethod method = verificationMethodMapper.selectById(methodId);
        if (method == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!StringUtils.hasText(methodName)) {
            throw BusinessException.of(400, "compliance.gate.invalidMethod");
        }
        method.setMethodName(methodName);
        method.setDescription(description);
        method.setEnabled(enabled ? 1 : 0);
        method.setEffectiveFrom(effectiveFrom);
        method.setEffectiveTo(effectiveTo);
        method.setUpdatedBy(SecurityUtils.currentUserId());
        int rows = verificationMethodMapper.updateById(method);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return method;
    }

    // ===== R23-P1-01 §9 subject create path（P0-4） =====

    @Override
    @Transactional
    public com.ses.entity.ComplianceExternalReviewerSubject createSubject(String subjectCode, String displayName,
                                                                          String organizationName) {
        if (!StringUtils.hasText(subjectCode) || !StringUtils.hasText(displayName)) {
            throw BusinessException.of(400, "compliance.gate.invalidSubject");
        }
        Long existing = reviewerSubjectMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ComplianceExternalReviewerSubject>()
                .eq(com.ses.entity.ComplianceExternalReviewerSubject::getTenantId, tenantId())
                .eq(com.ses.entity.ComplianceExternalReviewerSubject::getSubjectCode, subjectCode));
        if (existing != null && existing > 0) {
            throw BusinessException.of(400, "compliance.gate.duplicateSubject");
        }
        com.ses.entity.ComplianceExternalReviewerSubject subject = new com.ses.entity.ComplianceExternalReviewerSubject();
        subject.setTenantId(tenantId());
        subject.setSubjectCode(subjectCode);
        subject.setDisplayName(displayName);
        subject.setOrganizationName(organizationName);
        subject.setFingerprintKeyVersion(
                fingerprintKeyProvider.getCurrentKeyVersion(tenantId()));
        subject.setPersonFingerprintSnapshot(
                fingerprintService.personFingerprint(tenantId(), subject));
        subject.setCreatedBy(SecurityUtils.currentUserId());
        reviewerSubjectMapper.insert(subject);
        return subject;
    }

    @Override
    @Transactional
    public com.ses.entity.ComplianceReviewerQualification addQualification(
            Long reviewerSubjectId, Long reviewerTypeId, String registrationIdentifierMaskedSnapshot,
            String registrationIdentifierLabel) {
        if (reviewerSubjectId == null || reviewerTypeId == null) {
            throw BusinessException.of(400, "compliance.gate.invalidQualification");
        }
        if (reviewerSubjectMapper.selectById(reviewerSubjectId) == null) {
            throw BusinessException.of(400, "compliance.gate.reviewerSubjectNotFound");
        }
        if (reviewerTypeMapper.selectById(reviewerTypeId) == null) {
            throw BusinessException.of(400, "compliance.gate.invalidReviewerType");
        }
        Long existing = qualificationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ComplianceReviewerQualification>()
                .eq(com.ses.entity.ComplianceReviewerQualification::getTenantId, tenantId())
                .eq(com.ses.entity.ComplianceReviewerQualification::getReviewerSubjectId, reviewerSubjectId)
                .eq(com.ses.entity.ComplianceReviewerQualification::getReviewerTypeId, reviewerTypeId));
        if (existing != null && existing > 0) {
            throw BusinessException.of(400, "compliance.gate.duplicateQualification");
        }
        com.ses.entity.ComplianceReviewerQualification qualification =
                new com.ses.entity.ComplianceReviewerQualification();
        qualification.setTenantId(tenantId());
        qualification.setReviewerSubjectId(reviewerSubjectId);
        qualification.setReviewerTypeId(reviewerTypeId);
        qualification.setRegistrationIdentifierMaskedSnapshot(registrationIdentifierMaskedSnapshot);
        qualification.setRegistrationIdentifierLabel(registrationIdentifierLabel);
        qualification.setEnabled(1);
        qualification.setCreatedBy(SecurityUtils.currentUserId());
        qualificationMapper.insert(qualification);
        return qualification;
    }

    @Override
    public List<com.ses.entity.ComplianceReviewerQualification> listQualifications(Long reviewerSubjectId) {
        return qualificationMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceReviewerQualification>()
                .eq(com.ses.entity.ComplianceReviewerQualification::getTenantId, tenantId())
                .eq(com.ses.entity.ComplianceReviewerQualification::getReviewerSubjectId, reviewerSubjectId)
                .orderByAsc(com.ses.entity.ComplianceReviewerQualification::getId));
    }

    @Override
    public List<com.ses.dto.compliance.ComplianceEvidencePickerDto> searchEvidence(String query) {
        // P0-5: evidence pickerはallow-list（document/version/title/originalName/SHA-256/scan/createdAt）。
        // CLEANのみを返す（PENDING/INFECTEDはfail-closed・§4-6）。
        java.util.List<com.ses.entity.DocumentVersion> versions;
        if (StringUtils.hasText(query)) {
            versions = documentVersionMapper.selectList(new LambdaQueryWrapper<com.ses.entity.DocumentVersion>()
                    .eq(com.ses.entity.DocumentVersion::getTenantId, tenantId())
                    .eq(com.ses.entity.DocumentVersion::getScanStatus, "CLEAN")
                    .eq(com.ses.entity.DocumentVersion::getDeletedFlag, 0)
                    .and(w -> w.like(com.ses.entity.DocumentVersion::getOriginalName, query)
                            .or().like(com.ses.entity.DocumentVersion::getBusinessKey, query))
                    .orderByDesc(com.ses.entity.DocumentVersion::getCreatedAt)
                    .last("LIMIT 50"));
        } else {
            versions = documentVersionMapper.selectList(new LambdaQueryWrapper<com.ses.entity.DocumentVersion>()
                    .eq(com.ses.entity.DocumentVersion::getTenantId, tenantId())
                    .eq(com.ses.entity.DocumentVersion::getScanStatus, "CLEAN")
                    .eq(com.ses.entity.DocumentVersion::getDeletedFlag, 0)
                    .orderByDesc(com.ses.entity.DocumentVersion::getCreatedAt)
                    .last("LIMIT 50"));
        }
        return versions.stream().map(v -> {
            com.ses.dto.compliance.ComplianceEvidencePickerDto dto =
                    new com.ses.dto.compliance.ComplianceEvidencePickerDto();
            dto.setDocumentId(v.getDocumentId());
            dto.setVersionId(v.getId());
            dto.setDocumentTitle(v.getOriginalName());
            dto.setOriginalName(v.getOriginalName());
            dto.setSha256(v.getSha256());
            dto.setScanStatus(v.getScanStatus());
            dto.setCreatedAt(v.getCreatedAt());
            return dto;
        }).toList();
    }

    @Override
    public com.ses.dto.compliance.ComplianceManifestDto buildManifest(Long mappingId) {
        com.ses.entity.ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        com.ses.dto.compliance.ComplianceManifestDto dto = new com.ses.dto.compliance.ComplianceManifestDto();
        dto.setMappingId(version.getId());
        dto.setMappingVersion(version.getMappingVersion());
        dto.setMappingHash(version.getMappingHash());
        dto.setReviewPolicyHash(version.getReviewPolicyHash());
        dto.setStatus(version.getStatus());
        dto.setActiveSlot(version.getActiveSlot());
        dto.setFutureSlot(version.getFutureSlot());

        // sources
        List<com.ses.entity.ComplianceMappingSource> sources = complianceSourceMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingSource>()
                        .eq(com.ses.entity.ComplianceMappingSource::getMappingId, mappingId)
                        .orderByAsc(com.ses.entity.ComplianceMappingSource::getId));
        dto.setSources(sources.stream().map(s -> {
            com.ses.dto.compliance.ComplianceManifestDto.SourceEntry e =
                    new com.ses.dto.compliance.ComplianceManifestDto.SourceEntry();
            e.setSourceId(s.getId());
            e.setSourceCode(s.getSourceCode());
            e.setSourceUrl(s.getSourceUrl());
            e.setSourceVersion(s.getSourceVersion());
            return e;
        }).toList());

        // policy groups/types
        List<com.ses.entity.ComplianceMappingReviewRequirementGroup> groups =
                requirementGroupMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, tenantId())
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, mappingId)
                        .orderByAsc(com.ses.entity.ComplianceMappingReviewRequirementGroup::getSortOrder));
        List<Long> groupIds = groups.stream().map(com.ses.entity.ComplianceMappingReviewRequirementGroup::getId).toList();
        List<com.ses.entity.ComplianceMappingReviewRequirementType> types = groupIds.isEmpty() ? List.of() :
                requirementTypeMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                        .in(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds));
        java.util.List<com.ses.dto.compliance.ComplianceManifestDto.PolicyEntry> policy = new java.util.ArrayList<>();
        for (com.ses.entity.ComplianceMappingReviewRequirementGroup g : groups) {
            for (com.ses.entity.ComplianceMappingReviewRequirementType t : types) {
                if (!g.getId().equals(t.getRequirementGroupId())) {
                    continue;
                }
                com.ses.dto.compliance.ComplianceManifestDto.PolicyEntry e =
                        new com.ses.dto.compliance.ComplianceManifestDto.PolicyEntry();
                e.setGroupId(g.getId());
                e.setGroupCode(g.getRequirementGroupCode());
                e.setRequirementTypeId(t.getId());
                e.setReviewerTypeCodeSnapshot(t.getReviewerTypeCodeSnapshot());
                e.setCredentialRequiredSnapshot(t.getCredentialRequiredSnapshot());
                e.setQualificationVerificationRequiredSnapshot(t.getQualificationVerificationRequiredSnapshot());
                e.setActiveStatusVerificationRequiredSnapshot(t.getActiveStatusVerificationRequiredSnapshot());
                policy.add(e);
            }
        }
        dto.setPolicy(policy);

        // approvals
        dto.setApprovals(complianceApprovalEventMapper.selectByMapping(tenantId(), mappingId, "APPROVE")
                .stream().map(a -> {
            com.ses.dto.compliance.ComplianceManifestDto.ApprovalEntry e =
                    new com.ses.dto.compliance.ComplianceManifestDto.ApprovalEntry();
            e.setEventId(a.getId());
            e.setActorId(a.getActorId());
            e.setAction(a.getAction());
            e.setMappingHash(a.getMappingHash());
            e.setReviewPolicyHash(a.getReviewPolicyHash());
            e.setEvidenceDocumentVersionId(a.getEvidenceDocumentVersionId());
            e.setEvidenceDocumentHash(a.getEvidenceDocumentHash());
            e.setEvidenceScanStatus(a.getEvidenceScanStatus());
            return e;
        }).toList());

        // external reviews（SUBMITTED）・verification・adoption
        List<com.ses.entity.ComplianceExternalReviewEvent> reviews =
                externalReviewEventMapper.selectByMapping(tenantId(), mappingId);
        dto.setExternalReviews(reviews.stream().map(r -> {
            com.ses.dto.compliance.ComplianceManifestDto.ExternalReviewEntry e =
                    new com.ses.dto.compliance.ComplianceManifestDto.ExternalReviewEntry();
            e.setEventId(r.getId());
            e.setReviewChainId(r.getReviewChainId());
            e.setReviewerTypeCodeSnapshot(r.getReviewerTypeCodeSnapshot());
            e.setReviewerNameSnapshot(r.getReviewerNameSnapshot());
            e.setOrganizationSnapshot(r.getOrganizationSnapshot());
            e.setAction(r.getAction());
            e.setEvidenceDocumentId(r.getEvidenceDocumentId());
            return e;
        }).toList());

        java.util.List<com.ses.dto.compliance.ComplianceManifestDto.VerificationEntry> verifications =
                new java.util.ArrayList<>();
        java.util.List<com.ses.dto.compliance.ComplianceManifestDto.AdoptionEntry> adoptions =
                new java.util.ArrayList<>();
        for (com.ses.entity.ComplianceExternalReviewEvent r : reviews) {
            for (com.ses.entity.ComplianceExternalReviewerVerificationEvent v :
                    verificationEventMapper.selectBySubmittedReview(tenantId(), r.getId())) {
                com.ses.dto.compliance.ComplianceManifestDto.VerificationEntry e =
                        new com.ses.dto.compliance.ComplianceManifestDto.VerificationEntry();
                e.setEventId(v.getId());
                e.setVerificationKind(v.getVerificationKind());
                e.setResult(v.getResult());
                e.setReviewerSubjectId(v.getReviewerSubjectId());
                e.setReviewerTypeCodeSnapshot(v.getReviewerTypeCodeSnapshot());
                e.setFingerprintKeyVersion(v.getFingerprintKeyVersion());
                e.setEvidenceDocumentVersionId(v.getEvidenceDocumentVersionId());
                e.setEvidenceDocumentHash(v.getEvidenceDocumentHash());
                e.setReviewPolicyHash(v.getReviewPolicyHash());
                e.setMappingHash(v.getMappingHash());
                e.setExternalReviewChainId(v.getExternalReviewChainId());
                verifications.add(e);
            }
            for (com.ses.entity.ComplianceExternalReviewAdoptionEvent a :
                    complianceAdoptionEventMapper.selectChainBySubmittedReview(tenantId(), r.getId())) {
                com.ses.dto.compliance.ComplianceManifestDto.AdoptionEntry e =
                        new com.ses.dto.compliance.ComplianceManifestDto.AdoptionEntry();
                e.setEventId(a.getId());
                e.setAction(a.getAction());
                e.setReviewChainId(a.getReviewChainId());
                e.setIdentityVerificationEventId(a.getIdentityVerificationEventId());
                e.setQualificationVerificationEventId(a.getQualificationVerificationEventId());
                e.setActiveStatusVerificationEventId(a.getActiveStatusVerificationEventId());
                e.setAuthorshipVerificationEventId(a.getAuthorshipVerificationEventId());
                e.setMappingHash(a.getMappingHash());
                e.setReviewPolicyHash(a.getReviewPolicyHash());
                e.setEvidenceDocumentVersionId(a.getEvidenceDocumentVersionId());
                e.setEvidenceDocumentHash(a.getEvidenceDocumentHash());
                e.setAdoptedAt(a.getAdoptedAt());
                adoptions.add(e);
            }
        }
        dto.setVerifications(verifications);
        dto.setAdoptions(adoptions);
        return dto;
    }

    private String sha256Hex(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
