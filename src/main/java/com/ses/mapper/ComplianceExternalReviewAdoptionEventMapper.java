package com.ses.mapper;

import com.ses.entity.ComplianceExternalReviewAdoptionEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * adoption eventはINSERT/SELECTだけを公開する。UPDATE/DELETE APIを持たせない
 * （MySQL triggerでUPDATE/DELETE拒否・gate採用はAPPROVEDのみ・reducer正本はadopted_at, id）。
 */
@Mapper
public interface ComplianceExternalReviewAdoptionEventMapper {

    @Insert("INSERT INTO t_compliance_external_review_adoption_event "
            + "(tenant_id, action, review_chain_id, submitted_review_event_id, revoked_adoption_event_id, "
            + "identity_verification_event_id, qualification_verification_event_id, active_status_verification_event_id, "
            + "authorship_verification_event_id, mapping_id, mapping_version, mapping_hash, review_policy_version, "
            + "review_policy_hash, evidence_document_id, evidence_document_version_id, evidence_document_version, "
            + "evidence_document_hash, adopted_at, adopted_by, operation_id, correlation_id, idempotency_key) VALUES "
            + "(#{event.tenantId}, #{event.action}, #{event.reviewChainId}, #{event.submittedReviewEventId}, "
            + "#{event.revokedAdoptionEventId}, #{event.identityVerificationEventId}, "
            + "#{event.qualificationVerificationEventId}, #{event.activeStatusVerificationEventId}, "
            + "#{event.authorshipVerificationEventId}, #{event.mappingId}, #{event.mappingVersion}, "
            + "#{event.mappingHash}, #{event.reviewPolicyVersion}, #{event.reviewPolicyHash}, "
            + "#{event.evidenceDocumentId}, #{event.evidenceDocumentVersionId}, #{event.evidenceDocumentVersion}, "
            + "#{event.evidenceDocumentHash}, #{event.adoptedAt}, #{event.adoptedBy}, #{event.operationId}, "
            + "#{event.correlationId}, #{event.idempotencyKey})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") ComplianceExternalReviewAdoptionEvent event);

    @Select("SELECT * FROM t_compliance_external_review_adoption_event "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    ComplianceExternalReviewAdoptionEvent selectByTenantAndId(@Param("tenantId") String tenantId,
                                                               @Param("id") Long id);

    @Select("SELECT * FROM t_compliance_external_review_adoption_event "
            + "WHERE tenant_id = #{tenantId} AND review_chain_id = #{reviewChainId} "
            + "ORDER BY adopted_at, id")
    List<ComplianceExternalReviewAdoptionEvent> selectChain(@Param("tenantId") String tenantId,
                                                            @Param("reviewChainId") String reviewChainId);

    /** 指定submitted review eventを参照するadoption event一覧（初回adoption判定用）。 */
    @Select("SELECT * FROM t_compliance_external_review_adoption_event "
            + "WHERE tenant_id = #{tenantId} AND submitted_review_event_id = #{submittedReviewEventId} "
            + "ORDER BY adopted_at, id")
    List<ComplianceExternalReviewAdoptionEvent> selectChainBySubmittedReview(
            @Param("tenantId") String tenantId, @Param("submittedReviewEventId") Long submittedReviewEventId);

    /** 指定mappingのSUBMITTED review chain（adoptionの正本）を探索する。 */
    @Select("SELECT a.* FROM t_compliance_external_review_adoption_event a "
            + "JOIN t_compliance_external_review_event r ON r.tenant_id = a.tenant_id AND r.id = a.submitted_review_event_id "
            + "WHERE a.tenant_id = #{tenantId} AND r.mapping_id = #{mappingId} "
            + "ORDER BY a.adopted_at DESC, a.id DESC LIMIT 1")
    ComplianceExternalReviewAdoptionEvent selectLatestAdoptionByMapping(@Param("tenantId") String tenantId,
                                                                        @Param("mappingId") Long mappingId);
}
