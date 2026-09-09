package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.OwnershipRepairQueue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OwnershipRepairQueueMapper extends BaseMapper<OwnershipRepairQueue> {

    @Select("SELECT * FROM nf02_nf03_ownership_repair_queue WHERE status = 'PENDING' "
            + "ORDER BY created_at, id")
    List<OwnershipRepairQueue> selectPending();

    @Select("SELECT COUNT(*) FROM nf02_nf03_ownership_repair_queue WHERE status = 'PENDING'")
    long countPending();

    @Select("SELECT MIN(created_at) FROM nf02_nf03_ownership_repair_queue WHERE status = 'PENDING'")
    LocalDateTime selectOldestPendingAt();

    @Select("SELECT * FROM nf02_nf03_ownership_repair_queue WHERE id = #{id} AND status IN ('PENDING','CLAIMED') "
            + "FOR UPDATE")
    OwnershipRepairQueue selectForUpdate(@Param("id") Long id);

    @Update("UPDATE nf02_nf03_ownership_repair_queue SET status = 'RESOLVED', "
            + "repair_tenant_id = #{tenantId}, resolution_reason = #{reason}, evidence = #{evidence}, "
            + "resolved_at = #{resolvedAt}, resolved_by = #{resolvedBy}, last_checked_at = #{resolvedAt}, "
            + "incident_id = #{incidentId}, actor_tenant_id = #{actorTenantId}, evidence_hash = #{evidenceHash}, "
            + "approver_id = #{approverId}, version = version + 1 "
            + "WHERE id = #{id} AND status = 'CLAIMED' AND claim_token = #{claimToken} AND version = #{expectedVersion}")
    int markResolvedCas(@Param("id") Long id, @Param("tenantId") String tenantId,
                     @Param("reason") String reason, @Param("evidence") String evidence,
                     @Param("resolvedAt") LocalDateTime resolvedAt, @Param("resolvedBy") Long resolvedBy,
                     @Param("incidentId") Long incidentId, @Param("actorTenantId") String actorTenantId,
                     @Param("evidenceHash") String evidenceHash, @Param("approverId") Long approverId,
                     @Param("claimToken") String claimToken, @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE nf02_nf03_ownership_repair_queue SET assignee_user_id = #{assigneeUserId}, "
            + "status = 'CLAIMED', claim_token = #{claimToken}, claimed_by = #{assigneeUserId}, "
            + "claimed_at = #{checkedAt}, last_checked_at = #{checkedAt}, incident_id = #{incidentId}, "
            + "actor_tenant_id = #{actorTenantId}, version = version + 1 "
            + "WHERE id = #{id} AND status = 'PENDING' AND version = #{expectedVersion}")
    int claim(@Param("id") Long id, @Param("assigneeUserId") Long assigneeUserId,
              @Param("claimToken") String claimToken, @Param("checkedAt") LocalDateTime checkedAt,
              @Param("expectedVersion") Integer expectedVersion,
              @Param("incidentId") Long incidentId, @Param("actorTenantId") String actorTenantId);
}
