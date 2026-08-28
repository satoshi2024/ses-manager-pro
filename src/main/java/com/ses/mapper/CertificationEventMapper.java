package com.ses.mapper;

import com.ses.entity.CertificationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 資格eventはINSERT/SELECTのみ（append-only）。 */
@Mapper
public interface CertificationEventMapper {

    @Insert("INSERT INTO t_certification_event "
            + "(tenant_id, certification_record_id, event_type, supersedes_event_id, reason, actor_user_id, "
            + "actor_role_snapshot, occurred_at, effective_record_state, effective_acquired_on, effective_expires_on, "
            + "evidence_document_id, evidence_document_version_id, evidence_document_hash, idempotency_key, created_at) VALUES "
            + "(#{event.tenantId}, #{event.certificationRecordId}, #{event.eventType}, #{event.supersedesEventId}, "
            + "#{event.reason}, #{event.actorUserId}, #{event.actorRoleSnapshot}, #{event.occurredAt}, "
            + "#{event.effectiveRecordState}, #{event.effectiveAcquiredOn}, #{event.effectiveExpiresOn}, "
            + "#{event.evidenceDocumentId}, #{event.evidenceDocumentVersionId}, #{event.evidenceDocumentHash}, "
            + "#{event.idempotencyKey}, "
            + "#{event.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") CertificationEvent event);

    @Select("SELECT * FROM t_certification_event WHERE certification_record_id = #{recordId} ORDER BY occurred_at, id")
    List<CertificationEvent> selectByRecordId(@Param("recordId") Long recordId);

    @Select("SELECT * FROM t_certification_event WHERE tenant_id = #{tenantId} AND idempotency_key = #{idempotencyKey} LIMIT 1")
    CertificationEvent selectByIdempotencyKey(@Param("tenantId") String tenantId,
                                              @Param("idempotencyKey") String idempotencyKey);
}
