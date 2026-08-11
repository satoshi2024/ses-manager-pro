package com.ses.mapper;

import com.ses.entity.ComplianceMappingApprovalEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 承認eventはINSERT/SELECTだけを公開する。UPDATE/DELETE APIを持たせない。 */
@Mapper
public interface ComplianceMappingApprovalEventMapper {
    @Insert("INSERT INTO t_compliance_mapping_approval_event "
            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
            + "event_chain_id, target_event_id, supersedes_event_id, occurred_at, reason, evidence_document_id, "
            + "evidence_document_version_id, evidence_document_version, evidence_document_hash, operation_id, "
            + "correlation_id, idempotency_key) VALUES "
            + "(#{event.tenantId}, #{event.mappingId}, #{event.mappingVersion}, #{event.mappingHash}, "
            + "#{event.reviewPolicyHash}, #{event.assignmentId}, #{event.workplaceIdSnapshot}, #{event.actorId}, "
            + "#{event.actorDisplayNameSnapshot}, #{event.actorRoleSnapshot}, #{event.action}, #{event.eventChainId}, "
            + "#{event.targetEventId}, #{event.supersedesEventId}, #{event.occurredAt}, #{event.reason}, "
            + "#{event.evidenceDocumentId}, #{event.evidenceDocumentVersionId}, #{event.evidenceDocumentVersion}, "
            + "#{event.evidenceDocumentHash}, #{event.operationId}, #{event.correlationId}, #{event.idempotencyKey})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") ComplianceMappingApprovalEvent event);

    @Select("SELECT * FROM t_compliance_mapping_approval_event "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    ComplianceMappingApprovalEvent selectByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT * FROM t_compliance_mapping_approval_event "
            + "WHERE tenant_id = #{tenantId} AND event_chain_id = #{eventChainId} "
            + "ORDER BY occurred_at, id")
    List<ComplianceMappingApprovalEvent> selectChain(@Param("tenantId") String tenantId,
                                                      @Param("eventChainId") String eventChainId);
}
