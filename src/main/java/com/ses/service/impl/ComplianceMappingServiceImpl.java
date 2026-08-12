package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.compliance.ComplianceMappingSourceInput;
import com.ses.entity.ComplianceMappingSource;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.mapper.ComplianceMappingSourceMapper;
import com.ses.mapper.ComplianceMappingVersionMapper;
import com.ses.service.ComplianceMappingService;
import com.ses.service.compliance.ComplianceMappingCanonicalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * G2 mapping version管理（Phase A step 3の第一increment）。
 *  - create: canonicalizerでmapping_hashを計算（§6.2。client supplied hashは信頼しない）。
 *  - DRAFT→PROVISIONAL_REVIEWED: source completeness（§6.1: 96 stable ID・公式source・版・effective period）を
 *    検証してfreeze（hash再計算・mapping_version/effective periodの変更拒否）。
 *  - ACTIVE化は実actor承認event・資格保有者Review等の証跡gate（後続increment）。
 */
@Service
@RequiredArgsConstructor
public class ComplianceMappingServiceImpl implements ComplianceMappingService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PROVISIONAL_REVIEWED = "PROVISIONAL_REVIEWED";
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** §6.1のsource completeness: 公式source（4帳票＋INDEX）が揃っていること。 */
    private static final java.util.Set<String> REQUIRED_SOURCES = java.util.Set.of(
            "SRC-C", "SRC-E", "SRC-N", "SRC-L", "SRC-INDEX");

    private final ComplianceMappingVersionMapper versionMapper;
    private final ComplianceMappingSourceMapper sourceMapper;
    private final com.ses.mapper.ComplianceMappingReviewRequirementGroupMapper requirementGroupMapper;
    private final com.ses.mapper.ComplianceMappingReviewRequirementTypeMapper requirementTypeMapper;
    private final com.ses.mapper.ComplianceMappingApprovalEventMapper approvalEventMapper;
    private final com.ses.mapper.ComplianceMappingStatusEventMapper statusEventMapper;
    private final com.ses.mapper.ComplianceResponsibleAssignmentMapper assignmentMapper;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final ComplianceMappingCanonicalizer canonicalizer;

    @Override
    @Transactional
    public ComplianceMappingVersion create(String mappingCode, String mappingVersion,
                                           java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo,
                                           List<ComplianceMappingSourceInput> sources) {
        if (!StringUtils.hasText(mappingCode) || !StringUtils.hasText(mappingVersion)
                || effectiveFrom == null || effectiveTo == null || effectiveFrom.isAfter(effectiveTo)) {
            throw BusinessException.of(400, "compliance.gate.invalidMapping");
        }
        ComplianceMappingVersion version = new ComplianceMappingVersion();
        version.setTenantId("default");
        version.setMappingCode(mappingCode);
        version.setMappingVersion(mappingVersion);
        version.setEffectiveFrom(effectiveFrom);
        version.setEffectiveTo(effectiveTo);
        version.setStatus(STATUS_DRAFT);
        version.setCreatedBy(com.ses.common.util.SecurityUtils.currentUserId());

        List<ComplianceMappingSource> sourceEntities = new java.util.ArrayList<>();
        for (ComplianceMappingSourceInput input : sources == null ? List.<ComplianceMappingSourceInput>of() : sources) {
            if (!StringUtils.hasText(input.getSourceCode())) {
                throw BusinessException.of(400, "compliance.gate.invalidSource");
            }
            ComplianceMappingSource source = new ComplianceMappingSource();
            source.setTenantId("default");
            source.setSourceCode(input.getSourceCode());
            source.setSourceUrl(input.getSourceUrl());
            source.setSourceVersion(input.getSourceVersion());
            source.setConfirmedOn(input.getConfirmedOn());
            source.setEffectiveFrom(input.getEffectiveFrom());
            source.setEffectiveTo(input.getEffectiveTo());
            source.setCreatedBy(version.getCreatedBy());
            sourceEntities.add(source);
        }
        // canonicalizerはversion/sourceのidに依存しないため、insert前にhashを計算してNOT NULL制約を満たす。
        version.setMappingHash(canonicalizer.computeMappingHash(version, sourceEntities));
        // review_policy_hash（§6.3）: 現行のreview policy（group/type）から計算。policy未設定なら空policyの決定的hash。
        version.setReviewPolicyHash(canonicalizer.computeReviewPolicyHash(
                requirementGroupMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, "default")),
                requirementTypeMapper.selectList(new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, "default"))));
        versionMapper.insert(version);
        for (ComplianceMappingSource source : sourceEntities) {
            source.setMappingId(version.getId());
            sourceMapper.insert(source);
        }
        return version;
    }

    @Override
    @Transactional
    public ComplianceMappingVersion transition(Long mappingId, String toStatus) {
        ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (STATUS_PROVISIONAL_REVIEWED.equals(toStatus)) {
            if (!STATUS_DRAFT.equals(version.getStatus())) {
                throw BusinessException.of(400, "compliance.gate.invalidTransition");
            }
            assertSourcesComplete(version);
            version.setStatus(STATUS_PROVISIONAL_REVIEWED);
            recomputeHash(version);
        } else if (STATUS_ACTIVE.equals(toStatus)) {
            activate(version);
        } else {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }
        version.setUpdatedBy(com.ses.common.util.SecurityUtils.currentUserId());
        int rows = versionMapper.updateById(version);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return version;
    }

    /**
     * ACTIVE guard（G2-ACTIVE-01・証跡gate）:
     *  - PROVISIONAL_REVIEWEDであること
     *  - 実actor承認event（APPROVE）が存在し、そのmapping_hashが現在のcanonical hashと一致すること
     *  - 承認に使用されたassignmentがactivation時点でopen（active_slot=1）であること
     *  - tenantにactive_slot=1のACTIVE versionが無ければactive_slot=1（現在版）、あればfuture_slot=1（保留版）
     */
    private void activate(ComplianceMappingVersion version) {
        if (!STATUS_PROVISIONAL_REVIEWED.equals(version.getStatus())) {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }
        // canonical hash再解決（§6.2: DBから再計算し保存hashと比較）
        List<ComplianceMappingSource> sources = sourceMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingSource>()
                        .eq(ComplianceMappingSource::getMappingId, version.getId()));
        String currentHash = canonicalizer.computeMappingHash(version, sources);
        if (!currentHash.equals(version.getMappingHash())) {
            throw BusinessException.of(400, "compliance.gate.mappingHashMismatch");
        }
        List<com.ses.entity.ComplianceMappingApprovalEvent> approvals = approvalEventMapper.selectByMapping(
                "default", version.getId(), "APPROVE");
        if (approvals.isEmpty()) {
            throw BusinessException.of(400, "compliance.gate.approvalRequired");
        }
        com.ses.entity.ComplianceMappingApprovalEvent approval = approvals.get(approvals.size() - 1);
        if (!currentHash.equals(approval.getMappingHash())) {
            throw BusinessException.of(400, "compliance.gate.approvalHashMismatch");
        }
        com.ses.entity.ComplianceResponsibleAssignment assignment =
                assignmentMapper.selectById(approval.getAssignmentId());
        if (assignment == null || !Integer.valueOf(1).equals(assignment.getActiveSlot())) {
            throw BusinessException.of(400, "compliance.gate.assignmentNotOpen");
        }
        // slot管理: tenantにopen ACTIVEが無ければactive_slot=1、あればfuture_slot=1
        List<ComplianceMappingVersion> active = versionMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingVersion>()
                        .eq(ComplianceMappingVersion::getTenantId, "default")
                        .eq(ComplianceMappingVersion::getStatus, STATUS_ACTIVE)
                        .eq(ComplianceMappingVersion::getActiveSlot, 1));
        if (active.isEmpty()) {
            version.setActiveSlot(1);
            version.setFutureSlot(null);
        } else {
            version.setActiveSlot(null);
            version.setFutureSlot(1);
        }
        version.setStatus(STATUS_ACTIVE);
        version.setActivatedAt(java.time.LocalDateTime.now());
        version.setActivatedBy(com.ses.common.util.SecurityUtils.currentUserId());
        recordStatusEvent(version, STATUS_PROVISIONAL_REVIEWED, STATUS_ACTIVE);
    }

    /** append-only status event記録（G2-EVENT-01）。 */
    private void recordStatusEvent(ComplianceMappingVersion version, String before, String after) {
        com.ses.entity.SysUser actor = sysUserMapper.selectById(com.ses.common.util.SecurityUtils.currentUserId());
        com.ses.entity.ComplianceMappingStatusEvent event = new com.ses.entity.ComplianceMappingStatusEvent();
        event.setTenantId("default");
        event.setMappingId(version.getId());
        event.setMappingVersion(version.getMappingVersion());
        event.setMappingHash(version.getMappingHash());
        event.setReviewPolicyHash(version.getReviewPolicyHash());
        event.setBeforeStatus(before);
        event.setAfterStatus(after);
        event.setActorId(com.ses.common.util.SecurityUtils.currentUserId());
        event.setActorDisplayNameSnapshot(actor == null ? "" : actor.getRealName());
        event.setActorRoleSnapshot(actor == null ? "" : actor.getRole());
        event.setOccurredAt(java.time.LocalDateTime.now());
        event.setExpectedVersion(version.getVersion());
        event.setOperationId(java.util.UUID.randomUUID().toString());
        event.setCorrelationId(java.util.UUID.randomUUID().toString());
        statusEventMapper.insertEvent(event);
    }

    @Override
    public List<ComplianceMappingVersion> list() {
        return versionMapper.selectList(new LambdaQueryWrapper<ComplianceMappingVersion>()
                .eq(ComplianceMappingVersion::getTenantId, "default")
                .orderByDesc(ComplianceMappingVersion::getId));
    }

    @Override
    public ComplianceMappingVersion getById(Long mappingId) {
        ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return version;
    }

    /** §6.1: source completeness（96 stable IDに必要な公式source・版・確認日・effective period）。 */
    private void assertSourcesComplete(ComplianceMappingVersion version) {
        List<ComplianceMappingSource> sources = sourceMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingSource>()
                        .eq(ComplianceMappingSource::getMappingId, version.getId()));
        java.util.Set<String> present = new java.util.HashSet<>();
        for (ComplianceMappingSource source : sources) {
            if (StringUtils.hasText(source.getSourceCode()) && StringUtils.hasText(source.getSourceUrl())
                    && StringUtils.hasText(source.getSourceVersion()) && source.getConfirmedOn() != null) {
                present.add(source.getSourceCode());
            }
        }
        for (String required : REQUIRED_SOURCES) {
            if (!present.contains(required)) {
                throw BusinessException.of(400, "compliance.gate.sourceIncomplete", required);
            }
        }
    }

    /** canonicalizerでhashを再計算して保存する（mapping変更は新version・既存hashは更新しない原則に沿い、create/transition時のみ）。 */
    private void recomputeHash(ComplianceMappingVersion version) {
        List<ComplianceMappingSource> sources = sourceMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingSource>()
                        .eq(ComplianceMappingSource::getMappingId, version.getId()));
        String hash = canonicalizer.computeMappingHash(version, sources);
        version.setMappingHash(hash);
        versionMapper.updateById(version);
    }
}
