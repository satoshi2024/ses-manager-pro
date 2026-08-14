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
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

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
    private final com.ses.service.compliance.ComplianceMappingCanonicalizer canonicalizer;
    private final com.ses.service.compliance.ComplianceGateEvaluationService gateEvaluationService;
    private final com.ses.mapper.ComplianceExternalReviewAdoptionEventMapper adoptionEventMapper;
    private final com.ses.mapper.ComplianceExternalReviewerVerificationEventMapper verificationEventMapper;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final com.ses.service.compliance.ComplianceTenantResolver tenantResolver;

    private String tenantId() {
        return tenantResolver.currentTenantId();
    }

    @Override
    @Transactional
    public ComplianceMappingVersion create(String mappingCode, String mappingVersion,
                                           java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo,
                                           List<ComplianceMappingSourceInput> sources) {
        if (!StringUtils.hasText(mappingCode) || !StringUtils.hasText(mappingVersion)
                || effectiveFrom == null || (effectiveTo != null && effectiveFrom.isAfter(effectiveTo))) {
            throw BusinessException.of(400, "compliance.gate.invalidMapping");
        }
        ComplianceMappingVersion version = new ComplianceMappingVersion();
        version.setTenantId(tenantId());
        version.setMappingCode(mappingCode);
        version.setMappingVersion(mappingVersion);
        version.setEffectiveFrom(effectiveFrom);
        version.setEffectiveTo(effectiveTo);
        version.setStatus(STATUS_DRAFT);
        version.setCreatedBy(com.ses.common.util.SecurityUtils.currentUserId());

        java.time.LocalDate asOf = resolveAsOf();
        if (asOf.isBefore(effectiveFrom)) {
            List<ComplianceMappingVersion> futureList = versionMapper.selectList(
                    new LambdaQueryWrapper<ComplianceMappingVersion>()
                            .eq(ComplianceMappingVersion::getTenantId, tenantId())
                            .eq(ComplianceMappingVersion::getMappingCode, mappingCode)
                            .eq(ComplianceMappingVersion::getFutureSlot, 1));
            if (!futureList.isEmpty()) {
                throw BusinessException.of(409, "compliance.gate.futureSlotAlreadyExists");
            }
            version.setFutureSlot(1);
        }

        List<ComplianceMappingSource> sourceEntities = new java.util.ArrayList<>();
        for (ComplianceMappingSourceInput input : sources == null ? List.<ComplianceMappingSourceInput>of() : sources) {
            if (!StringUtils.hasText(input.getSourceCode())) {
                throw BusinessException.of(400, "compliance.gate.invalidSource");
            }
            ComplianceMappingSource source = new ComplianceMappingSource();
            source.setTenantId(tenantId());
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
        // review_policy_hash（§6.3）: 現行のreview policy（group/type）から計算。create時点はgroup/type未設定のため空policyの決定的hash。
        version.setReviewPolicyHash(canonicalizer.computeReviewPolicyHash(List.of(), List.of()));
        try {
            versionMapper.insert(version);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        for (ComplianceMappingSource source : sourceEntities) {
            source.setMappingId(version.getId());
            sourceMapper.insert(source);
        }
        return version;
    }

    @Override
    @Transactional
    public ComplianceMappingVersion transition(Long mappingId, String toStatus) {
        return transition(mappingId, toStatus, null);
    }

    @Override
    @Transactional
    public ComplianceMappingVersion transition(Long mappingId, String toStatus, Long approvalEventId) {
        ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (STATUS_PROVISIONAL_REVIEWED.equals(toStatus)) {
            if (!STATUS_DRAFT.equals(version.getStatus())) {
                throw BusinessException.of(400, "compliance.gate.invalidTransition");
            }
            assertSourcesComplete(version);
            assertPolicyNotEmpty(version);
            version.setStatus(STATUS_PROVISIONAL_REVIEWED);
            recomputeHash(version);
        } else if (STATUS_ACTIVE.equals(toStatus)) {
            activate(version, approvalEventId);
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

    @org.springframework.beans.factory.annotation.Value("${spring.jackson.time-zone:#{null}}")
    private String deploymentTimezone;

    private java.time.ZoneId resolveDeploymentZoneId() {
        if (!StringUtils.hasText(deploymentTimezone)) {
            throw BusinessException.of(409, "compliance.gate.timezoneUnavailable");
        }
        try {
            return java.time.ZoneId.of(deploymentTimezone.trim());
        } catch (Exception e) {
            throw BusinessException.of(409, "compliance.gate.timezoneUnavailable");
        }
    }

    private java.time.LocalDate resolveAsOf() {
        return java.time.LocalDate.now(resolveDeploymentZoneId());
    }

    /**
     * ACTIVE guard（G2-ACTIVE-01・証跡gate R8.1）:
     *  - PROVISIONAL_REVIEWEDであること
     *  - 非空レビューポリシー（1件以上のRequirement Group定義）を持つこと（P2-N1）
     *  - 指定のapprovalEventIdが存在し、action=APPROVEであること
     *  - そのapproval event以降にREVOKE/REJECTの取消イベントが存在しないこと
     *  - mapping_hash / review_policy_hash をDBから再解決・再計算し一致を確認すること
     *  - 承認に使用されたassignmentがopen（active_slot=1）かつworkplaceId一致であること
     *  - asOfがeffective period内（effective_from <= asOf <= effective_to）であること（P2-N2）
     *  - tenant/mappingCode単位でactive_slot=1のACTIVE versionが無ければactive_slot=1（現在版）、あればfuture_slot=1（2件目future候補は禁止）
     */
    private void activate(ComplianceMappingVersion version, Long approvalEventId) {
        if (!STATUS_PROVISIONAL_REVIEWED.equals(version.getStatus())) {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }
        assertPolicyNotEmpty(version);
        java.time.LocalDate asOf = resolveAsOf();
        if (version.getEffectiveFrom() != null && asOf.isBefore(version.getEffectiveFrom())) {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }
        if (version.getEffectiveTo() != null && asOf.isAfter(version.getEffectiveTo())) {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }

        if (approvalEventId == null) {
            throw BusinessException.of(400, "compliance.gate.approvalRequired");
        }
        com.ses.entity.ComplianceMappingApprovalEvent approval = approvalEventMapper.selectByTenantAndId(tenantId(), approvalEventId);
        if (approval == null || !version.getId().equals(approval.getMappingId()) || !"APPROVE".equals(approval.getAction())) {
            throw BusinessException.of(400, "compliance.gate.approvalRequired");
        }
        if (approvalEventMapper.countSubsequentRevokes(tenantId(), version.getId(), approvalEventId) > 0) {
            throw BusinessException.of(400, "compliance.gate.approvalRevoked");
        }

        // canonical mapping hash再解決（§6.2: DBから再計算し保存hashおよび承認hashと比較）
        List<ComplianceMappingSource> sources = sourceMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingSource>()
                        .eq(ComplianceMappingSource::getMappingId, version.getId()));
        String currentMappingHash = canonicalizer.computeMappingHash(version, sources);
        if (!currentMappingHash.equals(version.getMappingHash()) || !currentMappingHash.equals(approval.getMappingHash())) {
            throw BusinessException.of(400, "compliance.gate.mappingHashMismatch");
        }

        // canonical review policy hash再解決（§6.3: DBから再計算し保存hashおよび承認hashと比較）
        List<com.ses.entity.ComplianceMappingReviewRequirementGroup> groups = requirementGroupMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, tenantId())
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, version.getId()));
        List<Long> groupIds = groups.stream().map(com.ses.entity.ComplianceMappingReviewRequirementGroup::getId).toList();
        List<com.ses.entity.ComplianceMappingReviewRequirementType> types = groupIds.isEmpty() ? List.of() :
                requirementTypeMapper.selectList(
                        new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                                .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                                .in(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds));
        String currentPolicyHash = canonicalizer.computeReviewPolicyHash(groups, types);
        if (!currentPolicyHash.equals(version.getReviewPolicyHash()) || !currentPolicyHash.equals(approval.getReviewPolicyHash())) {
            throw BusinessException.of(400, "compliance.gate.policyHashMismatch");
        }

        // §4-8: ACTIVE/future promote/formal generateで共通のComplianceGateEvaluationServiceを利用する。
        // 旧ComplianceExternalReviewEvaluator（self-declared hash・latest evidence・旧APPROVED直接採用）は
        // gate正本から除外される。gateはAPPROVED adoption event（adopted_at, id reducer）のみ採用（§3.2）。
        com.ses.entity.ComplianceExternalReviewAdoptionEvent latestAdoption =
                adoptionEventMapper.selectLatestAdoptionByMapping(tenantId(), version.getId());
        if (latestAdoption == null) {
            throw BusinessException.of(400, "compliance.gate.externalReviewIncomplete");
        }
        boolean qualificationRequired = false;
        boolean activeStatusRequired = false;
        // frozen policyのflag（§G2-VERIFY-14・§8）: 採用typeのsnapshot flagがtrueなら該当verification必須。
        // 実装ではadoptionが参照するAUTHORSHIP verificationのreviewer type snapshotに従う。
        com.ses.entity.ComplianceExternalReviewerVerificationEvent authorshipVerification =
                verificationEventMapper.selectByTenantAndId(tenantId(), latestAdoption.getAuthorshipVerificationEventId());
        if (authorshipVerification != null) {
            Long reviewerTypeId = authorshipVerification.getReviewerTypeId();
            List<com.ses.entity.ComplianceMappingReviewRequirementType> frozenTypes = requirementTypeMapper.selectList(
                    new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getReviewerTypeId, reviewerTypeId));
            for (com.ses.entity.ComplianceMappingReviewRequirementType frozen : frozenTypes) {
                if (Integer.valueOf(1).equals(frozen.getCredentialRequiredSnapshot())) {
                    // credential_requiredは資格必須を表すため、QUALIFICATION/ACTIVE_STATUS必須として扱う
                    qualificationRequired = true;
                    activeStatusRequired = true;
                }
            }
        }
        gateEvaluationService.adopt(tenantId(), latestAdoption.getReviewChainId(), version, asOf,
                qualificationRequired, activeStatusRequired);

        // assignment再解決（openかつworkplaceId一致）
        if (approval.getAssignmentId() == null) {
            throw BusinessException.of(400, "compliance.gate.assignmentNotOpen");
        }
        com.ses.entity.ComplianceResponsibleAssignment assignment =
                assignmentMapper.selectById(approval.getAssignmentId());
        if (assignment == null || !Integer.valueOf(1).equals(assignment.getActiveSlot())
                || approval.getWorkplaceIdSnapshot() == null || !approval.getWorkplaceIdSnapshot().equals(assignment.getWorkplaceId())) {
            throw BusinessException.of(400, "compliance.gate.assignmentNotOpen");
        }

        // slot管理: tenant + mappingCode単位（uk_g2_mapping_active_slot/future_slot一致）でopen ACTIVEが無ければactive_slot=1、あればfuture_slot=1
        List<ComplianceMappingVersion> activeList = versionMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingVersion>()
                        .eq(ComplianceMappingVersion::getTenantId, tenantId())
                        .eq(ComplianceMappingVersion::getMappingCode, version.getMappingCode())
                        .eq(ComplianceMappingVersion::getStatus, STATUS_ACTIVE)
                        .eq(ComplianceMappingVersion::getActiveSlot, 1));
        if (activeList.isEmpty()) {
            Integer expectedVer = version.getVersion();
            version.setActiveSlot(1);
            version.setFutureSlot(null);
            version.setStatus(STATUS_ACTIVE);
            version.setActivatedAt(java.time.LocalDateTime.now());
            version.setActivatedBy(com.ses.common.util.SecurityUtils.currentUserId());
            recordStatusEvent(version, STATUS_PROVISIONAL_REVIEWED, STATUS_ACTIVE, expectedVer);
        } else {
            List<ComplianceMappingVersion> futureList = versionMapper.selectList(
                    new LambdaQueryWrapper<ComplianceMappingVersion>()
                            .eq(ComplianceMappingVersion::getTenantId, tenantId())
                            .eq(ComplianceMappingVersion::getMappingCode, version.getMappingCode())
                            .eq(ComplianceMappingVersion::getFutureSlot, 1)
                            .ne(ComplianceMappingVersion::getId, version.getId()));
            if (!futureList.isEmpty()) {
                throw BusinessException.of(400, "compliance.gate.futureSlotAlreadyExists");
            }
            version.setActiveSlot(null);
            version.setFutureSlot(1);
            // active_slotがNULLの行はstatus <> ACTIVEでなければDB CHECK違反になるためSTATUS_PROVISIONAL_REVIEWEDを維持
        }
    }

    @Override
    @Transactional
    public ComplianceMappingVersion promoteFutureToActive(Long mappingId) {
        ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!Integer.valueOf(1).equals(version.getFutureSlot())) {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }
        java.time.LocalDate asOf = resolveAsOf();
        if (version.getEffectiveFrom() != null && asOf.isBefore(version.getEffectiveFrom())) {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }
        if (version.getEffectiveTo() != null && asOf.isAfter(version.getEffectiveTo())) {
            throw BusinessException.of(400, "compliance.gate.invalidTransition");
        }

        // N5: 昇格前の mapping/policy hash 再検証
        List<ComplianceMappingSource> sources = sourceMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingSource>()
                        .eq(ComplianceMappingSource::getMappingId, version.getId()));
        String currentMappingHash = canonicalizer.computeMappingHash(version, sources);
        if (!currentMappingHash.equals(version.getMappingHash())) {
            throw BusinessException.of(400, "compliance.gate.mappingHashMismatch");
        }

        List<com.ses.entity.ComplianceMappingReviewRequirementGroup> groups = requirementGroupMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, tenantId())
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, version.getId()));
        List<Long> groupIds = groups.stream().map(com.ses.entity.ComplianceMappingReviewRequirementGroup::getId).toList();
        List<com.ses.entity.ComplianceMappingReviewRequirementType> types = groupIds.isEmpty() ? List.of() :
                requirementTypeMapper.selectList(
                        new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                                .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                                .in(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds));
        String currentPolicyHash = canonicalizer.computeReviewPolicyHash(groups, types);
        if (!currentPolicyHash.equals(version.getReviewPolicyHash())) {
            throw BusinessException.of(400, "compliance.gate.policyHashMismatch");
        }

        // P3-N1: 昇格前に既存承認のREVOKE再検証
        List<com.ses.entity.ComplianceMappingApprovalEvent> approvals =
                approvalEventMapper.selectByMapping(tenantId(), version.getId(), "APPROVE");
        for (com.ses.entity.ComplianceMappingApprovalEvent app : approvals) {
            if (approvalEventMapper.countSubsequentRevokes(tenantId(), version.getId(), app.getId()) > 0) {
                throw BusinessException.of(400, "compliance.gate.approvalRevoked");
            }
        }

        // N3: 旧ACTIVE supersede と新 ACTIVE 活性化で単一 operationId / correlationId を共有
        String sharedOperationId = java.util.UUID.randomUUID().toString();
        String sharedCorrelationId = java.util.UUID.randomUUID().toString();

        List<ComplianceMappingVersion> currentActiveList = versionMapper.selectList(
                new LambdaQueryWrapper<ComplianceMappingVersion>()
                        .eq(ComplianceMappingVersion::getTenantId, tenantId())
                        .eq(ComplianceMappingVersion::getMappingCode, version.getMappingCode())
                        .eq(ComplianceMappingVersion::getStatus, STATUS_ACTIVE)
                        .eq(ComplianceMappingVersion::getActiveSlot, 1));
        for (ComplianceMappingVersion oldActive : currentActiveList) {
            if (oldActive.getEffectiveFrom() != null && version.getEffectiveFrom() != null
                    && version.getEffectiveFrom().isBefore(oldActive.getEffectiveFrom())) {
                throw BusinessException.of(400, "compliance.gate.invalidTransition");
            }
            Integer oldExpectedVer = oldActive.getVersion();
            oldActive.setStatus(STATUS_SUPERSEDED);
            oldActive.setActiveSlot(null);
            oldActive.setFutureSlot(null);
            // P1-N1: 旧ACTIVEのeffective_toやmapping_hashは一切書き換えない（決定性・hash整合性の維持）
            oldActive.setUpdatedBy(com.ses.common.util.SecurityUtils.currentUserId());
            int rows = versionMapper.updateById(oldActive);
            if (rows == 0) {
                throw BusinessException.of(409, "contract.compliance.versionConflict");
            }
            recordStatusEvent(oldActive, STATUS_ACTIVE, STATUS_SUPERSEDED, oldExpectedVer, sharedOperationId, sharedCorrelationId);
        }
        String beforeStatus = version.getStatus();
        Integer expectedVer = version.getVersion();
        version.setStatus(STATUS_ACTIVE);
        version.setActiveSlot(1);
        version.setFutureSlot(null);
        version.setActivatedAt(java.time.LocalDateTime.now());
        version.setActivatedBy(com.ses.common.util.SecurityUtils.currentUserId());
        version.setUpdatedBy(com.ses.common.util.SecurityUtils.currentUserId());
        int rows = versionMapper.updateById(version);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        recordStatusEvent(version, beforeStatus, STATUS_ACTIVE, expectedVer, sharedOperationId, sharedCorrelationId);
        return version;
    }

    /**
     * §4-3（P0-FIX-2/3・G2-POL）: policy整合の単一検証（PROVISIONAL化・ACTIVE化・promote・generateで共用）。
     * - 最低1group
     * - 各group最低1type（空group/typeはskipせずinvalid frozen policyとしてfail-closed）
     * - minimum_distinct_reviewers >= 1
     * - review_policy_hashが現在のpolicyからの再計算と一致（freeze契約・snapshot/hash一致）
     */
    private void assertPolicyNotEmpty(ComplianceMappingVersion version) {
        List<com.ses.entity.ComplianceMappingReviewRequirementGroup> groups = requirementGroupMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, tenantId())
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, version.getId()));
        if (groups.isEmpty()) {
            throw BusinessException.of(400, "compliance.gate.policyInvalid");
        }
        for (com.ses.entity.ComplianceMappingReviewRequirementGroup group : groups) {
            if (group.getMinimumDistinctReviewers() == null || group.getMinimumDistinctReviewers() < 1) {
                throw BusinessException.of(400, "compliance.gate.policyInvalid");
            }
            Long typeCount = requirementTypeMapper.selectCount(
                    new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, group.getId()));
            if (typeCount == null || typeCount == 0) {
                throw BusinessException.of(400, "compliance.gate.policyInvalid");
            }
        }
        List<Long> groupIds = groups.stream().map(com.ses.entity.ComplianceMappingReviewRequirementGroup::getId).toList();
        List<com.ses.entity.ComplianceMappingReviewRequirementType> types = requirementTypeMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, tenantId())
                        .in(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds));
        String recomputed = canonicalizer.computeReviewPolicyHash(groups, types);
        if (!recomputed.equals(version.getReviewPolicyHash())) {
            throw BusinessException.of(400, "compliance.gate.policyHashMismatch");
        }
    }

    /** append-only status event記録（G2-EVENT-01）。P3-N2: expectedVersionは更新前の事前値を一意記録する。N3: UUID共有。 */
    private void recordStatusEvent(ComplianceMappingVersion version, String before, String after, Integer expectedVersion) {
        recordStatusEvent(version, before, after, expectedVersion, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString());
    }

    private void recordStatusEvent(ComplianceMappingVersion version, String before, String after, Integer expectedVersion, String operationId, String correlationId) {
        com.ses.entity.SysUser actor = sysUserMapper.selectById(com.ses.common.util.SecurityUtils.currentUserId());
        com.ses.entity.ComplianceMappingStatusEvent event = new com.ses.entity.ComplianceMappingStatusEvent();
        event.setTenantId(tenantId());
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
        event.setExpectedVersion(expectedVersion);
        event.setOperationId(operationId);
        event.setCorrelationId(correlationId);
        statusEventMapper.insertEvent(event);
    }

    @Override
    public List<ComplianceMappingVersion> list() {
        return versionMapper.selectList(new LambdaQueryWrapper<ComplianceMappingVersion>()
                .eq(ComplianceMappingVersion::getTenantId, tenantId())
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
