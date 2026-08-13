package com.ses.mapper;

import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * verification eventはINSERT/SELECTだけを公開する。UPDATE/DELETE APIを持たせない
 * （MySQL triggerでUPDATE/DELETE拒否・K2用途別FK列）。
 */
@Mapper
public interface ComplianceExternalReviewerVerificationEventMapper {

    @Insert("INSERT INTO t_compliance_external_reviewer_verification_event "
            + "(tenant_id, reviewer_type_id, reviewer_type_code_snapshot, reviewer_type_name_snapshot, "
            + "reviewer_subject_id, person_fingerprint_snapshot, qualification_fingerprint_snapshot, "
            + "fingerprint_key_version, verification_kind, result, method_code, authority_source_code, "
            + "authority_source_name, official_url_reference_snapshot, registration_identifier_encrypted, "
            + "registration_identifier_key_version, registration_identifier_cipher_format, "
            + "registration_identifier_masked_snapshot, checked_at, source_data_as_of, max_age_days_snapshot, "
            + "valid_until, checked_by, evidence_document_id, evidence_document_version_id, "
            + "evidence_document_version, evidence_document_hash, review_policy_version, review_policy_hash, "
            + "mapping_id, mapping_version, mapping_hash, external_review_event_id, external_review_chain_id, "
            + "submitted_review_event_id, revoked_verification_event_id, supersedes_verification_event_id, "
            + "operation_id, correlation_id, idempotency_key) VALUES "
            + "(#{event.tenantId}, #{event.reviewerTypeId}, #{event.reviewerTypeCodeSnapshot}, "
            + "#{event.reviewerTypeNameSnapshot}, #{event.reviewerSubjectId}, #{event.personFingerprintSnapshot}, "
            + "#{event.qualificationFingerprintSnapshot}, #{event.fingerprintKeyVersion}, #{event.verificationKind}, "
            + "#{event.result}, #{event.methodCode}, #{event.authoritySourceCode}, #{event.authoritySourceName}, "
            + "#{event.officialUrlReferenceSnapshot}, #{event.registrationIdentifierEncrypted}, "
            + "#{event.registrationIdentifierKeyVersion}, #{event.registrationIdentifierCipherFormat}, "
            + "#{event.registrationIdentifierMaskedSnapshot}, #{event.checkedAt}, #{event.sourceDataAsOf}, "
            + "#{event.maxAgeDaysSnapshot}, #{event.validUntil}, #{event.checkedBy}, #{event.evidenceDocumentId}, "
            + "#{event.evidenceDocumentVersionId}, #{event.evidenceDocumentVersion}, #{event.evidenceDocumentHash}, "
            + "#{event.reviewPolicyVersion}, #{event.reviewPolicyHash}, #{event.mappingId}, #{event.mappingVersion}, "
            + "#{event.mappingHash}, #{event.externalReviewEventId}, #{event.externalReviewChainId}, "
            + "#{event.submittedReviewEventId}, #{event.revokedVerificationEventId}, "
            + "#{event.supersedesVerificationEventId}, #{event.operationId}, #{event.correlationId}, "
            + "#{event.idempotencyKey})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") ComplianceExternalReviewerVerificationEvent event);

    @Select("SELECT * FROM t_compliance_external_reviewer_verification_event "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    ComplianceExternalReviewerVerificationEvent selectByTenantAndId(@Param("tenantId") String tenantId,
                                                                     @Param("id") Long id);

    @Select("SELECT * FROM t_compliance_external_reviewer_verification_event "
            + "WHERE tenant_id = #{tenantId} AND submitted_review_event_id = #{submittedReviewEventId} "
            + "ORDER BY checked_at, id")
    List<ComplianceExternalReviewerVerificationEvent> selectBySubmittedReview(
            @Param("tenantId") String tenantId, @Param("submittedReviewEventId") Long submittedReviewEventId);

    @Select("SELECT * FROM t_compliance_external_reviewer_verification_event "
            + "WHERE tenant_id = #{tenantId} AND reviewer_subject_id = #{reviewerSubjectId} "
            + "AND verification_kind = #{verificationKind} ORDER BY checked_at DESC, id DESC")
    List<ComplianceExternalReviewerVerificationEvent> selectLatestBySubjectAndKind(
            @Param("tenantId") String tenantId, @Param("reviewerSubjectId") Long reviewerSubjectId,
            @Param("verificationKind") String verificationKind);
}
