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
        // 既存open（active_slot=1・effective_to NULL）を終了する
        List<ComplianceResponsibleAssignment> open = assignmentMapper.selectList(
                new LambdaQueryWrapper<ComplianceResponsibleAssignment>()
                        .eq(ComplianceResponsibleAssignment::getTenantId, "default")
                        .eq(ComplianceResponsibleAssignment::getWorkplaceId, workplaceId)
                        .eq(ComplianceResponsibleAssignment::getActiveSlot, 1));
        for (ComplianceResponsibleAssignment current : open) {
            current.setEffectiveTo(now);
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
        assignment.setEffectiveTo(LocalDateTime.now());
        assignment.setActiveSlot(null);
        assignment.setEndedBy(SecurityUtils.currentUserId());
        assignment.setEndReason(reason);
        int rows = assignmentMapper.updateById(assignment);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return assignment;
    }
}
