package com.ses.mapper;

import com.ses.entity.ComplianceExternalReviewEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 外部Review eventはINSERT/SELECTだけを公開する。REVOKEも新規INSERTで表現する。 */
@Mapper
public interface ComplianceExternalReviewEventMapper {
    @Insert("INSERT INTO t_compliance_external_review_event "
            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, requirement_group_id, "
            + "requirement_group_code_snapshot, reviewer_type_id, reviewer_type_code_snapshot, reviewer_type_name_snapshot, "
            + "reviewer_name_snapshot, organization_snapshot, credential_snapshot_encrypted, credential_key_version, "
            + "credential_cipher_format, credential_masked_snapshot, reviewer_identity_hash, action, review_chain_id, "
            + "target_event_id, supersedes_event_id, reviewed_at, valid_until, recorded_at, evidence_document_id, "
            + "evidence_document_version_id, evidence_document_version, evidence_document_hash, recorded_by, operation_id, "
            + "correlation_id, idempotency_key) VALUES "
            + "(#{event.tenantId}, #{event.mappingId}, #{event.mappingVersion}, #{event.mappingHash}, #{event.reviewPolicyHash}, "
            + "#{event.requirementGroupId}, #{event.requirementGroupCodeSnapshot}, #{event.reviewerTypeId}, "
            + "#{event.reviewerTypeCodeSnapshot}, #{event.reviewerTypeNameSnapshot}, #{event.reviewerNameSnapshot}, "
            + "#{event.organizationSnapshot}, #{event.credentialSnapshotEncrypted}, #{event.credentialKeyVersion}, "
            + "#{event.credentialCipherFormat}, #{event.credentialMaskedSnapshot}, #{event.reviewerIdentityHash}, "
            + "#{event.action}, #{event.reviewChainId}, #{event.targetEventId}, #{event.supersedesEventId}, "
            + "#{event.reviewedAt}, #{event.validUntil}, #{event.recordedAt}, #{event.evidenceDocumentId}, "
            + "#{event.evidenceDocumentVersionId}, #{event.evidenceDocumentVersion}, #{event.evidenceDocumentHash}, "
            + "#{event.recordedBy}, #{event.operationId}, #{event.correlationId}, #{event.idempotencyKey})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") ComplianceExternalReviewEvent event);

    @Select("SELECT * FROM t_compliance_external_review_event "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    ComplianceExternalReviewEvent selectByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT * FROM t_compliance_external_review_event "
            + "WHERE tenant_id = #{tenantId} AND review_chain_id = #{reviewChainId} "
            + "ORDER BY reviewed_at, id")
    List<ComplianceExternalReviewEvent> selectChain(@Param("tenantId") String tenantId,
                                                     @Param("reviewChainId") String reviewChainId);

    @Select("SELECT * FROM t_compliance_external_review_event "
            + "WHERE tenant_id = #{tenantId} AND mapping_id = #{mappingId} AND requirement_group_id = #{groupId} "
            + "ORDER BY reviewed_at, id")
    List<ComplianceExternalReviewEvent> selectByMappingAndGroup(@Param("tenantId") String tenantId,
                                                                 @Param("mappingId") Long mappingId,
                                                                 @Param("groupId") Long groupId);

    @Select("SELECT * FROM t_compliance_external_review_event "
            + "WHERE tenant_id = #{tenantId} AND mapping_id = #{mappingId} "
            + "ORDER BY reviewed_at, id")
    List<ComplianceExternalReviewEvent> selectByMapping(@Param("tenantId") String tenantId,
                                                         @Param("mappingId") Long mappingId);
}
