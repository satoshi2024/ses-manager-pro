package com.ses.service;

import com.ses.entity.ComplianceExternalReviewerVerificationEvent;

import java.time.LocalDateTime;

/**
 * R23-P1-01 §3.3/§4: verification eventの記録・revoke・照合。
 * IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP の4 kindを
 * t_compliance_external_reviewer_verification_event へappend-only記録する。
 * 新規write pathはSUBMITTED review event（step 1）をtargetにする（§3.2 event順序）。
 */
public interface ComplianceExternalReviewVerificationService {

    /**
     * verification eventを記録する（§3.2 step 2）。
     * - submittedReviewEventIdが存在し、同一tenant・SUBMITTED actionであること
     * - reviewer subjectが存在すること（reviewerSubjectId）
     * - exact evidence（documentId＋versionId）をserver-side解決しCLEAN/SHA-256を検証
     * - AUTHORSHIP kindはmapping/policy/review binding必須（DB triggerでも担保）
     */
    ComplianceExternalReviewerVerificationEvent record(
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
            String idempotencyKey);

    /**
     * verification eventをrevokeする（§3.2 step 2・REVOKEDはrevoked_verification_event_id必須）。
     * targetは同一tenant・同一subject・同一kindの既存verification event。
     */
    ComplianceExternalReviewerVerificationEvent revoke(
            Long targetVerificationEventId,
            String reason,
            Long revokedBy,
            String idempotencyKey);
}
