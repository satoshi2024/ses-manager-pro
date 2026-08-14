package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.ComplianceExternalReviewEvent;
import com.ses.entity.ComplianceExternalReviewerSubject;
import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.ComplianceExternalReviewEventMapper;
import com.ses.mapper.ComplianceExternalReviewerSubjectMapper;
import com.ses.mapper.ComplianceExternalReviewerVerificationEventMapper;
import com.ses.mapper.ComplianceMappingVersionMapper;
import com.ses.service.ComplianceExternalReviewVerificationService;
import com.ses.service.compliance.ComplianceGateEvidenceResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * R23-P1-01 §3.3/§4: verification eventの記録・revoke実装（append-only）。
 * 新規write pathはSUBMITTED review eventをtargetにする（§3.2 event順序・後付けUPDATE禁止）。
 */
@Service
public class ComplianceExternalReviewVerificationServiceImpl
        implements ComplianceExternalReviewVerificationService {

    private final ComplianceExternalReviewEventMapper reviewEventMapper;
    private final ComplianceExternalReviewerSubjectMapper subjectMapper;
    private final ComplianceExternalReviewerVerificationEventMapper verificationEventMapper;
    private final ComplianceMappingVersionMapper versionMapper;
    private final ComplianceGateEvidenceResolver evidenceResolver;
    private final com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService;
    private final com.ses.service.compliance.ComplianceGateCredentialCryptoService credentialCryptoService;
    private final com.ses.service.compliance.ComplianceGateCredentialKeyProvider keyProvider;

    public ComplianceExternalReviewVerificationServiceImpl(
            ComplianceExternalReviewEventMapper reviewEventMapper,
            ComplianceExternalReviewerSubjectMapper subjectMapper,
            ComplianceExternalReviewerVerificationEventMapper verificationEventMapper,
            ComplianceMappingVersionMapper versionMapper,
            ComplianceGateEvidenceResolver evidenceResolver,
            com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService,
            com.ses.service.compliance.ComplianceGateCredentialCryptoService credentialCryptoService,
            com.ses.service.compliance.ComplianceGateCredentialKeyProvider keyProvider) {
        this.reviewEventMapper = reviewEventMapper;
        this.subjectMapper = subjectMapper;
        this.verificationEventMapper = verificationEventMapper;
        this.versionMapper = versionMapper;
        this.evidenceResolver = evidenceResolver;
        this.fingerprintService = fingerprintService;
        this.credentialCryptoService = credentialCryptoService;
        this.keyProvider = keyProvider;
    }

    @Override
    @Transactional
    public ComplianceExternalReviewerVerificationEvent record(
            Long submittedReviewEventId,
            Long reviewerSubjectId,
            Long reviewerTypeId,
            String verificationKind,
            String result,
            String methodCode,
            String authoritySourceCode,
            String authoritySourceName,
            String officialUrlReference,
            String registrationIdentifier,
            LocalDateTime checkedAt,
            LocalDateTime sourceDataAsOf,
            Integer maxAgeDays,
            LocalDateTime validUntil,
            Long checkedBy,
            Long evidenceDocumentId,
            Long evidenceDocumentVersionId,
            String reviewPolicyVersion,
            String reviewPolicyHash,
            Long mappingId,
            String mappingVersion,
            String mappingHash,
            Long externalReviewEventId,
            String externalReviewChainId,
            String idempotencyKey) {
        if (submittedReviewEventId == null || reviewerSubjectId == null || reviewerTypeId == null
                || !StringUtils.hasText(verificationKind) || !StringUtils.hasText(result)
                || checkedAt == null || checkedBy == null) {
            throw BusinessException.of(400, "compliance.gate.invalidVerification");
        }
        // SUBMITTED review eventが存在し、同一tenant・SUBMITTED actionであること
        ComplianceExternalReviewEvent submitted = reviewEventMapper.selectByTenantAndId("default", submittedReviewEventId);
        if (submitted == null || !"SUBMITTED".equalsIgnoreCase(submitted.getAction())) {
            throw BusinessException.of(400, "compliance.gate.verificationTargetInvalid");
        }
        // P0-6: reviewer subjectが存在すること（person-stable）
        ComplianceExternalReviewerSubject subject = subjectMapper.selectById(reviewerSubjectId);
        if (subject == null) {
            throw BusinessException.of(400, "compliance.gate.reviewerSubjectNotFound");
        }
        // P0-6: reviewer typeはsubmitted reviewのtypeと一致すること（cross-type混在拒否）
        if (!submitted.getReviewerTypeId().equals(reviewerTypeId)) {
            throw BusinessException.of(400, "compliance.gate.verificationTypeMismatch");
        }
        // P0-6: exact evidenceは必須（§4-5/6: evidence NULL/non-CLEAN/不存在/hash不一致は全て拒否）
        DocumentVersion evidence = evidenceResolver.resolve("default", evidenceDocumentId, evidenceDocumentVersionId);
        // P0-6: AUTHORSHIP以外はmax_age_days必須（§3.7: 未設定/不正値はfail-closed）
        boolean authorship = "REVIEW_AUTHORSHIP".equals(verificationKind);
        if (!authorship && (maxAgeDays == null || maxAgeDays < 1)) {
            throw BusinessException.of(400, "compliance.gate.verificationMaxAgeRequired");
        }
        // AUTHORSHIP kindはmapping/policy/review binding必須（DB triggerでも担保）
        if (authorship && (mappingId == null || !StringUtils.hasText(mappingVersion)
                || !StringUtils.hasText(mappingHash) || externalReviewEventId == null
                || !StringUtils.hasText(externalReviewChainId))) {
            throw BusinessException.of(400, "compliance.gate.authorshipBindingRequired");
        }
        // P0-6: cross-chain混在拒否 — 全kindでexternal review chainがsubmittedと一致すること（§G2-VERIFY-11/12）
        if (externalReviewEventId != null && !submitted.getId().equals(externalReviewEventId)) {
            throw BusinessException.of(400, "compliance.gate.verificationChainMismatch");
        }
        if (StringUtils.hasText(externalReviewChainId)
                && !submitted.getReviewChainId().equals(externalReviewChainId)) {
            throw BusinessException.of(400, "compliance.gate.verificationChainMismatch");
        }
        if (mappingId != null && !submitted.getMappingId().equals(mappingId)) {
            throw BusinessException.of(400, "compliance.gate.verificationMappingMismatch");
        }
        // REVIEW_AUTHORSHIPのmapping一致検証（§G2-VERIFY-12）
        if (authorship && mappingId != null) {
            ComplianceMappingVersion mapping = versionMapper.selectById(mappingId);
            if (mapping == null || !mappingVersion.equals(mapping.getMappingVersion())
                    || !mappingHash.equals(mapping.getMappingHash())) {
                throw BusinessException.of(400, "compliance.gate.authorshipMappingMismatch");
            }
        }

        String opId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey
                : "MAPPING-VERIFY:" + submittedReviewEventId + ":" + reviewerSubjectId + ":" + verificationKind + ":" + UUID.randomUUID();

        ComplianceExternalReviewerVerificationEvent event = new ComplianceExternalReviewerVerificationEvent();
        event.setTenantId("default");
        event.setReviewerTypeId(reviewerTypeId);
        event.setReviewerTypeCodeSnapshot(submitted.getReviewerTypeCodeSnapshot());
        event.setReviewerTypeNameSnapshot(submitted.getReviewerTypeNameSnapshot());
        event.setReviewerSubjectId(reviewerSubjectId);
        // §9 fingerprint domain分離（R23-S3-P1-01）: personとqualificationは別domainのtenant-HMAC。
        event.setPersonFingerprintSnapshot(fingerprintService.personFingerprint("default", subject));
        event.setQualificationFingerprintSnapshot(fingerprintService.qualificationFingerprint(
                "default", subject, submitted.getReviewerTypeCodeSnapshot(), registrationIdentifier));
        event.setFingerprintKeyVersion(subject.getFingerprintKeyVersion() != null
                ? subject.getFingerprintKeyVersion() : keyProvider.getCurrentKeyVersion());
        event.setVerificationKind(verificationKind);
        event.setResult(result);
        event.setMethodCode(methodCode);
        event.setAuthoritySourceCode(authoritySourceCode);
        event.setAuthoritySourceName(authoritySourceName);
        event.setOfficialUrlReferenceSnapshot(officialUrlReference);
        event.setCheckedAt(checkedAt);
        event.setSourceDataAsOf(sourceDataAsOf);
        event.setMaxAgeDaysSnapshot(maxAgeDays);
        event.setValidUntil(validUntil);
        event.setCheckedBy(checkedBy);
        if (evidence != null) {
            event.setEvidenceDocumentId(evidence.getDocumentId());
            event.setEvidenceDocumentVersionId(evidence.getId());
            event.setEvidenceDocumentVersion(String.valueOf(evidence.getVersionNo()));
            event.setEvidenceDocumentHash(evidence.getSha256());
        }
        event.setReviewPolicyVersion(reviewPolicyVersion);
        event.setReviewPolicyHash(reviewPolicyHash);
        event.setMappingId(mappingId);
        event.setMappingVersion(mappingVersion);
        event.setMappingHash(mappingHash);
        event.setExternalReviewEventId(externalReviewEventId);
        event.setExternalReviewChainId(externalReviewChainId);
        event.setSubmittedReviewEventId(submittedReviewEventId);
        event.setOperationId(opId);
        event.setCorrelationId(correlationId);
        event.setIdempotencyKey(key);
        // §3.3/§7（R23-S3-P2-01）: registration identifierはAES-GCM（CGC1 envelope）で暗号化し、
        // key version/cipher formatと共に保存する。My Numberは保存しない（§7）。
        if (StringUtils.hasText(registrationIdentifier)) {
            String raw = registrationIdentifier.trim();
            String envelope = credentialCryptoService.encrypt(
                    "default", mappingId, mappingVersion, opId, raw);
            event.setRegistrationIdentifierEncrypted(envelope);
            event.setRegistrationIdentifierKeyVersion(keyProvider.getCurrentKeyVersion());
            event.setRegistrationIdentifierCipherFormat(
                    com.ses.service.compliance.ComplianceGateCredentialCryptoServiceImpl.CIPHER_FORMAT_CGC1);
            event.setRegistrationIdentifierMaskedSnapshot(
                    raw.length() > 4 ? "****" + raw.substring(raw.length() - 4) : "VALIDATED");
        }
        try {
            verificationEventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return event;
    }

    @Override
    @Transactional
    public ComplianceExternalReviewerVerificationEvent revoke(
            Long targetVerificationEventId, String reason, Long revokedBy, String idempotencyKey) {
        if (targetVerificationEventId == null || !StringUtils.hasText(reason) || revokedBy == null) {
            throw BusinessException.of(400, "compliance.gate.invalidVerificationRevoke");
        }
        ComplianceExternalReviewerVerificationEvent target =
                verificationEventMapper.selectByTenantAndId("default", targetVerificationEventId);
        if (target == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if ("REVOKED".equals(target.getResult())) {
            throw BusinessException.of(400, "compliance.gate.verificationAlreadyRevoked");
        }
        ComplianceExternalReviewerVerificationEvent event = new ComplianceExternalReviewerVerificationEvent();
        event.setTenantId("default");
        event.setReviewerTypeId(target.getReviewerTypeId());
        event.setReviewerTypeCodeSnapshot(target.getReviewerTypeCodeSnapshot());
        event.setReviewerTypeNameSnapshot(target.getReviewerTypeNameSnapshot());
        event.setReviewerSubjectId(target.getReviewerSubjectId());
        event.setPersonFingerprintSnapshot(target.getPersonFingerprintSnapshot());
        event.setQualificationFingerprintSnapshot(target.getQualificationFingerprintSnapshot());
        event.setFingerprintKeyVersion(target.getFingerprintKeyVersion());
        event.setVerificationKind(target.getVerificationKind());
        event.setResult("REVOKED");
        event.setMethodCode(target.getMethodCode());
        event.setAuthoritySourceCode(target.getAuthoritySourceCode());
        event.setAuthoritySourceName(target.getAuthoritySourceName());
        event.setCheckedAt(target.getCheckedAt());
        event.setCheckedBy(revokedBy);
        event.setSubmittedReviewEventId(target.getSubmittedReviewEventId());
        event.setRevokedVerificationEventId(target.getId());
        event.setOperationId(UUID.randomUUID().toString());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setIdempotencyKey(StringUtils.hasText(idempotencyKey) ? idempotencyKey
                : "MAPPING-VERIFY-REVOKE:" + target.getId() + ":" + UUID.randomUUID());
        try {
            verificationEventMapper.insertEvent(event);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return event;
    }

    @Override
    public List<ComplianceExternalReviewerVerificationEvent> listBySubmittedReview(Long submittedReviewEventId) {
        if (submittedReviewEventId == null) {
            return List.of();
        }
        return verificationEventMapper.selectBySubmittedReview("default", submittedReviewEventId);
    }
}
