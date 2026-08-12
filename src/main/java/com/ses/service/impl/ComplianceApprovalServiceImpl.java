package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.ComplianceMappingApprovalEvent;
import com.ses.entity.ComplianceMappingReviewRequirementGroup;
import com.ses.entity.ComplianceMappingReviewRequirementType;
import com.ses.entity.ComplianceMappingSource;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.entity.ComplianceResponsibleAssignment;
import com.ses.entity.SysUser;
import com.ses.mapper.ComplianceMappingApprovalEventMapper;
import com.ses.mapper.ComplianceMappingReviewRequirementGroupMapper;
import com.ses.mapper.ComplianceMappingReviewRequirementTypeMapper;
import com.ses.mapper.ComplianceMappingSourceMapper;
import com.ses.mapper.ComplianceMappingVersionMapper;
import com.ses.mapper.ComplianceResponsibleAssignmentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.ComplianceApprovalService;
import com.ses.service.compliance.ComplianceMappingCanonicalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * G2 mapping approval（Phase A step 3・証跡2）。
 *  - 対象mappingはPROVISIONAL_REVIEWEDであること
 *  - 実actorは対象workplaceの現行open assignment（active_slot=1）の指名者本人であること
 *  - mapping_hash/review_policy_hashはcanonicalizerから再計算（client supplied hashは信頼しない）
 *  - idempotency_keyで重複記録を防ぐ
 */
@Service
@RequiredArgsConstructor
public class ComplianceApprovalServiceImpl implements ComplianceApprovalService {

    private final ComplianceMappingVersionMapper versionMapper;
    private final ComplianceMappingSourceMapper sourceMapper;
    private final ComplianceMappingReviewRequirementGroupMapper requirementGroupMapper;
    private final ComplianceMappingReviewRequirementTypeMapper requirementTypeMapper;
    private final ComplianceResponsibleAssignmentMapper assignmentMapper;
    private final ComplianceMappingApprovalEventMapper approvalEventMapper;
    private final ComplianceMappingCanonicalizer canonicalizer;
    private final SysUserMapper sysUserMapper;

    @Override
    @Transactional
    public ComplianceMappingApprovalEvent approve(Long mappingId, Long workplaceId, String reason, Long evidenceDocumentId) {
        ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!ComplianceMappingServiceImpl.STATUS_PROVISIONAL_REVIEWED.equals(version.getStatus())) {
            throw BusinessException.of(400, "compliance.gate.approvalTargetStatus");
        }
        if (!StringUtils.hasText(reason)) {
            throw BusinessException.of(400, "compliance.gate.findingNoteRequired");
        }
        Long actorId = SecurityUtils.currentUserId();
        if (actorId == null) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        // 実actor=現行open assignmentの指名者（同一workplace）
        List<ComplianceResponsibleAssignment> open = assignmentMapper.selectList(
                new LambdaQueryWrapper<ComplianceResponsibleAssignment>()
                        .eq(ComplianceResponsibleAssignment::getTenantId, "default")
                        .eq(ComplianceResponsibleAssignment::getWorkplaceId, workplaceId)
                        .eq(ComplianceResponsibleAssignment::getActiveSlot, 1));
        if (open.isEmpty() || !actorId.equals(open.get(0).getUserId())) {
            throw BusinessException.of(403, "compliance.gate.actorNotAssigned");
        }
        ComplianceResponsibleAssignment assignment = open.get(0);

        // canonical hash再計算（§6.2 mapping_hash・§6.3 review_policy_hash）
        List<ComplianceMappingSource> sources = sourceMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingSource>()
                        .eq(ComplianceMappingSource::getMappingId, mappingId));
        String mappingHash = canonicalizer.computeMappingHash(version, sources);
        List<ComplianceMappingReviewRequirementGroup> groups = requirementGroupMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingReviewRequirementGroup>()
                        .eq(ComplianceMappingReviewRequirementGroup::getTenantId, "default")
                        .eq(ComplianceMappingReviewRequirementGroup::getMappingId, mappingId));
        List<Long> groupIds = groups.stream().map(ComplianceMappingReviewRequirementGroup::getId).toList();
        List<ComplianceMappingReviewRequirementType> types = groupIds.isEmpty() ? List.of() :
                requirementTypeMapper.selectList(
                        new LambdaQueryWrapper<ComplianceMappingReviewRequirementType>()
                                .eq(ComplianceMappingReviewRequirementType::getTenantId, "default")
                                .in(ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds));
        String reviewPolicyHash = canonicalizer.computeReviewPolicyHash(groups, types);

        SysUser actor = sysUserMapper.selectById(actorId);
        ComplianceMappingApprovalEvent event = new ComplianceMappingApprovalEvent();
        event.setTenantId("default");
        event.setMappingId(mappingId);
        event.setMappingVersion(version.getMappingVersion());
        event.setMappingHash(mappingHash);
        event.setReviewPolicyHash(reviewPolicyHash);
        event.setAssignmentId(assignment.getId());
        event.setWorkplaceIdSnapshot(workplaceId);
        event.setActorId(actorId);
        event.setActorDisplayNameSnapshot(actor == null ? String.valueOf(actorId) : actor.getRealName());
        event.setActorRoleSnapshot(actor == null ? "" : actor.getRole());
        event.setAction("APPROVE");
        event.setEventChainId(UUID.randomUUID().toString());
        event.setOccurredAt(LocalDateTime.now());
        event.setReason(reason);
        event.setEvidenceDocumentId(evidenceDocumentId);
        event.setOperationId(UUID.randomUUID().toString());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setIdempotencyKey("MAPPING:APPROVE:" + mappingId + ":" + actorId + ":" + mappingHash + ":" + reviewPolicyHash);
        try {
            approvalEventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return event;
    }
}
