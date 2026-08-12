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
            // ACTIVE化は実actor承認event・資格保有者Review等の証跡gate（後続incrementで実装）。
            throw BusinessException.of(400, "compliance.gate.activeGated");
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
