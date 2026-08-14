package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ComplianceExternalReviewAdoptionEvent;
import com.ses.entity.ComplianceExternalReviewEvent;
import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.ComplianceExternalReviewAdoptionEventMapper;
import com.ses.mapper.ComplianceExternalReviewEventMapper;
import com.ses.mapper.ComplianceExternalReviewerVerificationEventMapper;
import com.ses.mapper.ComplianceMappingVersionMapper;
import com.ses.service.ComplianceExternalReviewAdoptionService;
import com.ses.service.compliance.ComplianceGateEvaluationService;
import com.ses.service.compliance.ComplianceGateEvidenceResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * R23-P1-01 §3.2/§4: adoption eventの記録・revoke実装（append-only・EXTERNAL_REVIEW_ADOPT / EXTERNAL_REVIEW_REVOKE）。
 * gateはAPPROVED adoption eventのみ採用（§G2-VERIFY-09）。reducer正本はadopted_at, id（§3.2 K3）。
 */
@Service
public class ComplianceExternalReviewAdoptionServiceImpl implements ComplianceExternalReviewAdoptionService {

    private final ComplianceExternalReviewEventMapper reviewEventMapper;
    private final ComplianceExternalReviewerVerificationEventMapper verificationEventMapper;
    private final ComplianceExternalReviewAdoptionEventMapper adoptionEventMapper;
    private final ComplianceMappingVersionMapper versionMapper;
    private final com.ses.mapper.ComplianceMappingReviewRequirementTypeMapper requirementTypeMapper;
    private final ComplianceGateEvidenceResolver evidenceResolver;
    private final ComplianceGateEvaluationService gateEvaluationService;

    public ComplianceExternalReviewAdoptionServiceImpl(
            ComplianceExternalReviewEventMapper reviewEventMapper,
            ComplianceExternalReviewerVerificationEventMapper verificationEventMapper,
            ComplianceExternalReviewAdoptionEventMapper adoptionEventMapper,
            ComplianceMappingVersionMapper versionMapper,
            com.ses.mapper.ComplianceMappingReviewRequirementTypeMapper requirementTypeMapper,
            ComplianceGateEvidenceResolver evidenceResolver,
            ComplianceGateEvaluationService gateEvaluationService) {
        this.reviewEventMapper = reviewEventMapper;
        this.verificationEventMapper = verificationEventMapper;
        this.adoptionEventMapper = adoptionEventMapper;
        this.versionMapper = versionMapper;
        this.requirementTypeMapper = requirementTypeMapper;
        this.evidenceResolver = evidenceResolver;
        this.gateEvaluationService = gateEvaluationService;
    }

    @Override
    @Transactional
    public ComplianceExternalReviewAdoptionEvent approve(
            Long submittedReviewEventId,
            Long identityVerificationEventId,
            Long qualificationVerificationEventId,
            Long activeStatusVerificationEventId,
            Long authorshipVerificationEventId,
            Long evidenceDocumentId,
            Long evidenceDocumentVersionId,
            String idempotencyKey) {
        if (submittedReviewEventId == null || identityVerificationEventId == null || authorshipVerificationEventId == null) {
            throw BusinessException.of(400, "compliance.gate.invalidAdoption");
        }
        ComplianceExternalReviewEvent submitted =
                reviewEventMapper.selectByTenantAndId("default", submittedReviewEventId);
        if (submitted == null || !"SUBMITTED".equalsIgnoreCase(submitted.getAction())) {
            throw BusinessException.of(400, "compliance.gate.verificationTargetInvalid");
        }
        ComplianceMappingVersion version = versionMapper.selectById(submitted.getMappingId());
        if (version == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // mapping/policy snapshot一致（§G2-VERIFY-12・§3.6: review_policy_version正本=mapping_version）
        if (!version.getMappingVersion().equals(submitted.getMappingVersion())
                || !version.getMappingHash().equals(submitted.getMappingHash())) {
            throw BusinessException.of(400, "compliance.gate.mappingHashMismatch");
        }
        // 初回adoption（APPROVEDまたはREJECTED）のみ許可（§3.2・DB triggerでも保証）
        assertNoPriorAdoption(submittedReviewEventId);
        LocalDate asOf = LocalDate.now();

        // 当該frozen policyが要求するverification setの検証（§3.2 K3・§G2-VERIFY-03）
        gateEvaluationService.verifyRequired("IDENTITY", identityVerificationEventId, "default", asOf);
        gateEvaluationService.verifyRequired("REVIEW_AUTHORSHIP", authorshipVerificationEventId, "default", asOf);
        boolean qualificationRequired = false;
        boolean activeStatusRequired = false;
        ComplianceExternalReviewerVerificationEvent authorship =
                verificationEventMapper.selectByTenantAndId("default", authorshipVerificationEventId);
        if (authorship != null) {
            List<com.ses.entity.ComplianceMappingReviewRequirementType> frozenTypes = requirementTypeMapper.selectList(
                    new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, "default")
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getReviewerTypeId,
                                    authorship.getReviewerTypeId()));
            for (com.ses.entity.ComplianceMappingReviewRequirementType frozen : frozenTypes) {
                if (Integer.valueOf(1).equals(frozen.getCredentialRequiredSnapshot())) {
                    qualificationRequired = true;
                    activeStatusRequired = true;
                }
            }
        }
        if (qualificationRequired) {
            gateEvaluationService.verifyRequired("QUALIFICATION", qualificationVerificationEventId, "default", asOf);
        }
        if (activeStatusRequired) {
            gateEvaluationService.verifyRequired("ACTIVE_STATUS", activeStatusVerificationEventId, "default", asOf);
        }
        // exact evidence（§4-5/6/7）
        DocumentVersion evidence = evidenceResolver.resolve("default", evidenceDocumentId, evidenceDocumentVersionId);

        ComplianceExternalReviewAdoptionEvent event = new ComplianceExternalReviewAdoptionEvent();
        event.setTenantId("default");
        event.setAction("APPROVED");
        event.setReviewChainId(submitted.getReviewChainId());
        event.setSubmittedReviewEventId(submittedReviewEventId);
        event.setIdentityVerificationEventId(identityVerificationEventId);
        event.setQualificationVerificationEventId(qualificationVerificationEventId);
        event.setActiveStatusVerificationEventId(activeStatusVerificationEventId);
        event.setAuthorshipVerificationEventId(authorshipVerificationEventId);
        event.setMappingId(version.getId());
        event.setMappingVersion(version.getMappingVersion());
        event.setMappingHash(version.getMappingHash());
        event.setReviewPolicyVersion(version.getMappingVersion());
        event.setReviewPolicyHash(version.getReviewPolicyHash());
        event.setEvidenceDocumentId(evidence.getDocumentId());
        event.setEvidenceDocumentVersionId(evidence.getId());
        event.setEvidenceDocumentVersion(String.valueOf(evidence.getVersionNo()));
        event.setEvidenceDocumentHash(evidence.getSha256());
        event.setAdoptedAt(LocalDateTime.now());
        event.setAdoptedBy(com.ses.common.util.SecurityUtils.currentUserId());
        event.setOperationId(UUID.randomUUID().toString());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setIdempotencyKey(StringUtils.hasText(idempotencyKey) ? idempotencyKey
                : "ADOPT:" + submittedReviewEventId + ":" + UUID.randomUUID());
        try {
            adoptionEventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return event;
    }

    @Override
    @Transactional
    public ComplianceExternalReviewAdoptionEvent reject(Long submittedReviewEventId, String reason, String idempotencyKey) {
        if (submittedReviewEventId == null || !StringUtils.hasText(reason)) {
            throw BusinessException.of(400, "compliance.gate.invalidAdoption");
        }
        ComplianceExternalReviewEvent submitted =
                reviewEventMapper.selectByTenantAndId("default", submittedReviewEventId);
        if (submitted == null || !"SUBMITTED".equalsIgnoreCase(submitted.getAction())) {
            throw BusinessException.of(400, "compliance.gate.verificationTargetInvalid");
        }
        assertNoPriorAdoption(submittedReviewEventId);
        ComplianceExternalReviewAdoptionEvent event = new ComplianceExternalReviewAdoptionEvent();
        event.setTenantId("default");
        event.setAction("REJECTED");
        event.setReviewChainId(submitted.getReviewChainId());
        event.setSubmittedReviewEventId(submittedReviewEventId);
        event.setAdoptedAt(LocalDateTime.now());
        event.setAdoptedBy(com.ses.common.util.SecurityUtils.currentUserId());
        event.setOperationId(UUID.randomUUID().toString());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setIdempotencyKey(StringUtils.hasText(idempotencyKey) ? idempotencyKey
                : "REJECT:" + submittedReviewEventId + ":" + UUID.randomUUID());
        try {
            adoptionEventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return event;
    }

    @Override
    @Transactional
    public ComplianceExternalReviewAdoptionEvent revoke(Long targetAdoptionEventId, String reason, String idempotencyKey) {
        if (targetAdoptionEventId == null || !StringUtils.hasText(reason)) {
            throw BusinessException.of(400, "compliance.gate.invalidAdoption");
        }
        ComplianceExternalReviewAdoptionEvent target =
                adoptionEventMapper.selectByTenantAndId("default", targetAdoptionEventId);
        if (target == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!"APPROVED".equals(target.getAction())) {
            throw BusinessException.of(400, "compliance.gate.adoptionRevokeTargetInvalid");
        }
        ComplianceExternalReviewAdoptionEvent event = new ComplianceExternalReviewAdoptionEvent();
        event.setTenantId("default");
        event.setAction("REVOKED");
        event.setReviewChainId(target.getReviewChainId());
        event.setSubmittedReviewEventId(target.getSubmittedReviewEventId());
        event.setRevokedAdoptionEventId(target.getId());
        event.setAdoptedAt(LocalDateTime.now());
        event.setAdoptedBy(com.ses.common.util.SecurityUtils.currentUserId());
        event.setOperationId(UUID.randomUUID().toString());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setIdempotencyKey(StringUtils.hasText(idempotencyKey) ? idempotencyKey
                : "ADOPT-REVOKE:" + target.getId() + ":" + UUID.randomUUID());
        try {
            adoptionEventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return event;
    }

    @Override
    public List<ComplianceExternalReviewAdoptionEvent> listBySubmittedReview(Long submittedReviewEventId) {
        return adoptionEventMapper.selectChainBySubmittedReview("default", submittedReviewEventId);
    }

    private void assertNoPriorAdoption(Long submittedReviewEventId) {
        List<ComplianceExternalReviewAdoptionEvent> chain =
                adoptionEventMapper.selectChainBySubmittedReview("default", submittedReviewEventId);
        if (!chain.isEmpty()) {
            throw BusinessException.of(409, "compliance.gate.adoptionAlreadyExists");
        }
    }
}
