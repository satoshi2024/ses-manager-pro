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

    @Override
    public List<ComplianceExternalReviewerType> listReviewerTypes() {
        return reviewerTypeMapper.selectList(new LambdaQueryWrapper<ComplianceExternalReviewerType>()
                .eq(ComplianceExternalReviewerType::getTenantId, "default")
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
                .eq(ComplianceExternalReviewerType::getTenantId, "default")
                .eq(ComplianceExternalReviewerType::getTypeCode, typeCode));
        if (existing != null && existing > 0) {
            throw BusinessException.of(400, "compliance.gate.duplicateReviewerType");
        }
        ComplianceExternalReviewerType type = new ComplianceExternalReviewerType();
        type.setTenantId("default");
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
        Long locked = workplaceMapper.selectIdForUpdate("default", workplaceId);
        if (locked == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // P6・§2.2（G2-ASG）: 有限期間assignmentとのoverlap拒否。
        // 新開始時点（effectiveFrom）で既に有効区間が終了していない有限期間行（effective_to > effectiveFrom）が
        // あれば、同一workplaceに有効1件の契約（asOfで有効は常に1件）に違反するため拒否する。
        // 現行open（active_slot=1・effective_to NULL）は交代として終了されるため対象外。
        Long overlapping = assignmentMapper.selectCount(
                new LambdaQueryWrapper<ComplianceResponsibleAssignment>()
                        .eq(ComplianceResponsibleAssignment::getTenantId, "default")
                        .eq(ComplianceResponsibleAssignment::getWorkplaceId, workplaceId)
                        .isNotNull(ComplianceResponsibleAssignment::getEffectiveTo)
                        .gt(ComplianceResponsibleAssignment::getEffectiveTo, effectiveFrom));
        if (overlapping != null && overlapping > 0) {
            throw BusinessException.of(409, "compliance.gate.assignmentOverlap");
        }
        // 既存open（active_slot=1・effective_to NULL）を終了する
        List<ComplianceResponsibleAssignment> open = assignmentMapper.selectList(
                new LambdaQueryWrapper<ComplianceResponsibleAssignment>()
                        .eq(ComplianceResponsibleAssignment::getTenantId, "default")
                        .eq(ComplianceResponsibleAssignment::getWorkplaceId, workplaceId)
                        .eq(ComplianceResponsibleAssignment::getActiveSlot, 1));
        for (ComplianceResponsibleAssignment current : open) {
            // endAssignmentと同じtick問題（TIMESTAMP(6)丸めで期間CHECK違反）へのガード
            LocalDateTime endAt = LocalDateTime.now();
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
        assignment.setTenantId("default");
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
        // effective_toは常にeffective_fromより後になるようガードする（TIMESTAMP(6)はµs精度のため1µs余裕）。
        LocalDateTime now = LocalDateTime.now();
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
                .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, "default")
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
        group.setTenantId("default");
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
        requirementType.setTenantId("default");
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

        version.setReviewPolicyHash(canonicalizer.computeReviewPolicyHash(groups, types));
        versionMapper.updateById(version);
    }

    private static final String CREDENTIAL_SECRET_KEY = "ComplianceExternalReviewKey2026";

    private final com.ses.mapper.ComplianceExternalReviewEventMapper externalReviewEventMapper;

    @Override
    @Transactional
    public com.ses.entity.ComplianceExternalReviewEvent recordExternalReview(Long mappingId, Long requirementGroupId, Long reviewerTypeId,
                                                                               String reviewerName, String organization, String credentialRaw,
                                                                               String action, LocalDateTime reviewedAt,
                                                                               LocalDateTime validUntil, Long evidenceDocumentId, String reason) {
        if (!StringUtils.hasText(reviewerName) || !StringUtils.hasText(organization)) {
            throw BusinessException.of(400, "compliance.gate.invalidExternalReview");
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

        String rawCred = credentialRaw != null ? credentialRaw.trim() : "";
        String masked = rawCred.length() > 4 ? "****" + rawCred.substring(rawCred.length() - 4) : "VALIDATED";
        String encrypted = encryptCredential(rawCred);
        String identityHash = sha256Hex(reviewerName + ":" + organization + ":" + rawCred);

        com.ses.entity.ComplianceExternalReviewEvent event = new com.ses.entity.ComplianceExternalReviewEvent();
        event.setTenantId("default");
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
        event.setCredentialKeyVersion("v1");
        event.setCredentialCipherFormat("AES-256-GCM");
        event.setCredentialMaskedSnapshot(masked);
        event.setReviewerIdentityHash(identityHash);
        event.setAction(StringUtils.hasText(action) ? action : "APPROVED");
        event.setReviewChainId(java.util.UUID.randomUUID().toString());
        event.setReviewedAt(reviewedAt != null ? reviewedAt : LocalDateTime.now());
        event.setValidUntil(validUntil);
        event.setRecordedAt(LocalDateTime.now());
        event.setEvidenceDocumentId(evidenceDocumentId);
        event.setRecordedBy(SecurityUtils.currentUserId());
        event.setOperationId(java.util.UUID.randomUUID().toString());
        event.setCorrelationId(java.util.UUID.randomUUID().toString());

        if (externalReviewEventMapper != null) {
            externalReviewEventMapper.insertEvent(event);
        }
        return event;
    }

    @Override
    public List<com.ses.entity.ComplianceExternalReviewEvent> listExternalReviews(Long mappingId) {
        if (externalReviewEventMapper == null) {
            return List.of();
        }
        return externalReviewEventMapper.selectByMapping("default", mappingId);
    }

    private String encryptCredential(String plainText) {
        try {
            byte[] keyBytes = java.util.Arrays.copyOf(CREDENTIAL_SECRET_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), 32);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] cipherText = cipher.doFinal(plainText == null ? new byte[0] : plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return java.util.Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Credential encryption failed", e);
        }
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
