package com.ses.mapper;

import com.ses.entity.ComplianceMappingStatusEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 状態eventはINSERT/SELECTだけを公開する。current rowの状態更新とは分離する。 */
@Mapper
public interface ComplianceMappingStatusEventMapper {
    @Insert("INSERT INTO t_compliance_mapping_status_event "
            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, before_status, after_status, "
            + "actor_id, actor_display_name_snapshot, actor_role_snapshot, occurred_at, expected_version, "
            + "gate_snapshot_hash, operation_id, correlation_id, reason) VALUES "
            + "(#{event.tenantId}, #{event.mappingId}, #{event.mappingVersion}, #{event.mappingHash}, "
            + "#{event.reviewPolicyHash}, #{event.beforeStatus}, #{event.afterStatus}, #{event.actorId}, "
            + "#{event.actorDisplayNameSnapshot}, #{event.actorRoleSnapshot}, #{event.occurredAt}, #{event.expectedVersion}, "
            + "#{event.gateSnapshotHash}, #{event.operationId}, #{event.correlationId}, #{event.reason})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") ComplianceMappingStatusEvent event);

    @Select("SELECT * FROM t_compliance_mapping_status_event "
            + "WHERE tenant_id = #{tenantId} AND id = #{id}")
    ComplianceMappingStatusEvent selectByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT * FROM t_compliance_mapping_status_event "
            + "WHERE tenant_id = #{tenantId} AND mapping_id = #{mappingId} "
            + "ORDER BY occurred_at, id")
    List<ComplianceMappingStatusEvent> selectByMapping(@Param("tenantId") String tenantId,
                                                        @Param("mappingId") Long mappingId);
}
