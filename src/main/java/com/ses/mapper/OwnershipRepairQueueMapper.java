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

    /**
     * 現在tenantの証拠（conflicting_tenant_id）に紐づくPENDINGのみ。
     * 他tenantの行や証拠tenant不明行は一覧に出さない（break-glassでも越境不可）。
     */
    @Select("SELECT * FROM nf02_nf03_ownership_repair_queue WHERE status = 'PENDING' "
            + "AND conflicting_tenant_id = #{tenantId} "
            + "ORDER BY created_at, id")
    List<OwnershipRepairQueue> selectPending(@Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM nf02_nf03_ownership_repair_queue WHERE status = 'PENDING' "
            + "AND conflicting_tenant_id = #{tenantId}")
    long countPending(@Param("tenantId") String tenantId);

    @Select("SELECT MIN(created_at) FROM nf02_nf03_ownership_repair_queue WHERE status = 'PENDING' "
            + "AND conflicting_tenant_id = #{tenantId}")
    LocalDateTime selectOldestPendingAt(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM nf02_nf03_ownership_repair_queue WHERE id = #{id} "
            + "AND conflicting_tenant_id = #{tenantId} "
            + "AND status IN ('PENDING','CLAIMED') "
            + "FOR UPDATE")
    OwnershipRepairQueue selectForUpdate(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Update("UPDATE nf02_nf03_ownership_repair_queue SET status = 'RESOLVED', "
            + "repair_tenant_id = #{tenantId}, resolution_reason = #{reason}, evidence = #{evidence}, "
            + "resolved_at = #{resolvedAt}, resolved_by = #{resolvedBy}, last_checked_at = #{resolvedAt}, "
            + "incident_id = #{incidentId}, actor_tenant_id = #{actorTenantId}, evidence_hash = #{evidenceHash}, "
            + "approver_id = #{approverId}, version = version + 1 "
            + "WHERE id = #{id} AND status = 'CLAIMED' AND claim_token = #{claimToken} "
            + "AND version = #{expectedVersion} AND conflicting_tenant_id = #{tenantId}")
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
            + "WHERE id = #{id} AND status = 'PENDING' AND version = #{expectedVersion} "
            + "AND conflicting_tenant_id = #{actorTenantId}")
    int claim(@Param("id") Long id, @Param("assigneeUserId") Long assigneeUserId,
              @Param("claimToken") String claimToken, @Param("checkedAt") LocalDateTime checkedAt,
              @Param("expectedVersion") Integer expectedVersion,
              @Param("incidentId") Long incidentId, @Param("actorTenantId") String actorTenantId);
}
