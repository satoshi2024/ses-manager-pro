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
    private final com.ses.service.compliance.ComplianceTenantResolver tenantResolver;

    public ComplianceExternalReviewVerificationServiceImpl(
            ComplianceExternalReviewEventMapper reviewEventMapper,
            ComplianceExternalReviewerSubjectMapper subjectMapper,
            ComplianceExternalReviewerVerificationEventMapper verificationEventMapper,
            ComplianceMappingVersionMapper versionMapper,
            ComplianceGateEvidenceResolver evidenceResolver,
            com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService,
            com.ses.service.compliance.ComplianceGateCredentialCryptoService credentialCryptoService,
            com.ses.service.compliance.ComplianceGateCredentialKeyProvider keyProvider,
            com.ses.service.compliance.ComplianceTenantResolver tenantResolver) {
        this.reviewEventMapper = reviewEventMapper;
        this.subjectMapper = subjectMapper;
        this.verificationEventMapper = verificationEventMapper;
        this.versionMapper = versionMapper;
        this.evidenceResolver = evidenceResolver;
        this.fingerprintService = fingerprintService;
        this.credentialCryptoService = credentialCryptoService;
        this.keyProvider = keyProvider;
        this.tenantResolver = tenantResolver;
    }

    private String tenantId() {
        return tenantResolver.currentTenantId();
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
        ComplianceExternalReviewEvent submitted = reviewEventMapper.selectByTenantAndId(tenantId(), submittedReviewEventId);
        if (submitted == null || !"SUBMITTED".equalsIgnoreCase(submitted.getAction())) {
            throw BusinessException.of(400, "compliance.gate.verificationTargetInvalid");
        }
        // P0-6/P1-2: reviewer subjectが存在すること（person-stable・tenant境界付き解決）
        ComplianceExternalReviewerSubject subject = subjectMapper.selectByTenantAndId(tenantId(), reviewerSubjectId);
        if (subject == null) {
            throw BusinessException.of(400, "compliance.gate.reviewerSubjectNotFound");
        }
        // P0-6: reviewer typeはsubmitted reviewのtypeと一致すること（cross-type混在拒否）
        if (!submitted.getReviewerTypeId().equals(reviewerTypeId)) {
            throw BusinessException.of(400, "compliance.gate.verificationTypeMismatch");
        }
        // P0-6: exact evidenceは必須（§4-5/6: evidence NULL/non-CLEAN/不存在/hash不一致は全て拒否）
        DocumentVersion evidence = evidenceResolver.resolve(tenantId(), evidenceDocumentId, evidenceDocumentVersionId);
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

        // P1-3: idempotency replay — 同一key＋同一canonical request hashは元eventを200 replay（§3.6）
        // registration identifierはAES-GCM暗号化されraw値が復元不可のため、hash比較はmasked表現で統一する。
        String requestHash = canonicalRequestHash(submittedReviewEventId, reviewerSubjectId, reviewerTypeId,
                verificationKind, result, methodCode, authoritySourceCode, authoritySourceName,
                officialUrlReference, maskRegistrationIdentifier(registrationIdentifier), checkedAt,
                sourceDataAsOf, maxAgeDays, validUntil, evidenceDocumentId, evidenceDocumentVersionId,
                mappingId, mappingVersion, mappingHash, externalReviewEventId, externalReviewChainId);
        ComplianceExternalReviewerVerificationEvent existing =
                verificationEventMapper.selectByIdempotencyKey(tenantId(), key);
        if (existing != null) {
            String existingHash = existing.getIdempotencyKey() == null ? null
                    : existingIdempotencyRequestHash(existing);
            if (existingHash != null && existingHash.equals(requestHash)) {
                return existing; // 200 replay
            }
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }

        ComplianceExternalReviewerVerificationEvent event = new ComplianceExternalReviewerVerificationEvent();
        event.setTenantId(tenantId());
        event.setReviewerTypeId(reviewerTypeId);
        event.setReviewerTypeCodeSnapshot(submitted.getReviewerTypeCodeSnapshot());
        event.setReviewerTypeNameSnapshot(submitted.getReviewerTypeNameSnapshot());
        event.setReviewerSubjectId(reviewerSubjectId);
        // §9 fingerprint domain分離（R23-S3-P1-01）: personとqualificationは別domainのtenant-HMAC。
        event.setPersonFingerprintSnapshot(fingerprintService.personFingerprint(tenantId(), subject));
        event.setQualificationFingerprintSnapshot(fingerprintService.qualificationFingerprint(
                tenantId(), subject, submitted.getReviewerTypeCodeSnapshot(), registrationIdentifier));
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
                    tenantId(), mappingId, mappingVersion, opId, raw);
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
                verificationEventMapper.selectByTenantAndId(tenantId(), targetVerificationEventId);
        if (target == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if ("REVOKED".equals(target.getResult())) {
            throw BusinessException.of(400, "compliance.gate.verificationAlreadyRevoked");
        }
        ComplianceExternalReviewerVerificationEvent event = new ComplianceExternalReviewerVerificationEvent();
        event.setTenantId(tenantId());
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
        return verificationEventMapper.selectBySubmittedReview(tenantId(), submittedReviewEventId);
    }

    // ===== P1-3: idempotency replay用canonical request hash =====

    private String canonicalRequestHash(Long submittedReviewEventId, Long reviewerSubjectId, Long reviewerTypeId,
                                        String verificationKind, String result, String methodCode,
                                        String authoritySourceCode, String authoritySourceName,
                                        String officialUrlReference, String registrationIdentifier,
                                        LocalDateTime checkedAt, LocalDateTime sourceDataAsOf, Integer maxAgeDays,
                                        LocalDateTime validUntil, Long evidenceDocumentId, Long evidenceDocumentVersionId,
                                        Long mappingId, String mappingVersion, String mappingHash,
                                        Long externalReviewEventId, String externalReviewChainId) {
        StringBuilder sb = new StringBuilder();
        sb.append("VERIFY|").append(submittedReviewEventId).append('|').append(reviewerSubjectId)
                .append('|').append(reviewerTypeId).append('|').append(nullSafe(verificationKind))
                .append('|').append(nullSafe(result)).append('|').append(nullSafe(methodCode))
                .append('|').append(nullSafe(authoritySourceCode)).append('|').append(nullSafe(authoritySourceName))
                .append('|').append(nullSafe(officialUrlReference)).append('|').append(nullSafe(registrationIdentifier))
                .append('|').append(checkedAt).append('|').append(sourceDataAsOf).append('|').append(maxAgeDays)
                .append('|').append(validUntil).append('|').append(evidenceDocumentId)
                .append('|').append(evidenceDocumentVersionId).append('|').append(mappingId)
                .append('|').append(nullSafe(mappingVersion)).append('|').append(nullSafe(mappingHash))
                .append('|').append(externalReviewEventId).append('|').append(nullSafe(externalReviewChainId));
        return sha256Hex(sb.toString());
    }

    /** 既存eventのidempotency request hash（eventフィールドから再構成）。 */
    private String existingIdempotencyRequestHash(ComplianceExternalReviewerVerificationEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("VERIFY|").append(e.getSubmittedReviewEventId()).append('|').append(e.getReviewerSubjectId())
                .append('|').append(e.getReviewerTypeId()).append('|').append(nullSafe(e.getVerificationKind()))
                .append('|').append(nullSafe(e.getResult())).append('|').append(nullSafe(e.getMethodCode()))
                .append('|').append(nullSafe(e.getAuthoritySourceCode())).append('|').append(nullSafe(e.getAuthoritySourceName()))
                .append('|').append(nullSafe(e.getOfficialUrlReferenceSnapshot())).append('|').append(nullSafe(e.getRegistrationIdentifierMaskedSnapshot()))
                .append('|').append(e.getCheckedAt()).append('|').append(e.getSourceDataAsOf()).append('|').append(e.getMaxAgeDaysSnapshot())
                .append('|').append(e.getValidUntil()).append('|').append(e.getEvidenceDocumentId())
                .append('|').append(e.getEvidenceDocumentVersionId()).append('|').append(e.getMappingId())
                .append('|').append(nullSafe(e.getMappingVersion())).append('|').append(nullSafe(e.getMappingHash()))
                .append('|').append(e.getExternalReviewEventId()).append('|').append(nullSafe(e.getExternalReviewChainId()));
        return sha256Hex(sb.toString());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 暗号化時のmasked表現と同一の変換（§3.3: 末尾4桁のみ露出）。 */
    private String maskRegistrationIdentifier(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() > 4 ? "****" + trimmed.substring(trimmed.length() - 4) : "VALIDATED";
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
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256計算に失敗しました", ex);
        }
    }
}
