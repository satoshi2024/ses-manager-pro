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

    @Update("UPDATE nf02_nf03_ownership_repair_queue SET status = 'RESOLVED', "
            + "repair_tenant_id = #{tenantId}, resolution_reason = #{reason}, evidence = #{evidence}, "
            + "resolved_at = #{resolvedAt}, resolved_by = #{resolvedBy}, last_checked_at = #{resolvedAt} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markResolved(@Param("id") Long id, @Param("tenantId") String tenantId,
                     @Param("reason") String reason, @Param("evidence") String evidence,
                     @Param("resolvedAt") LocalDateTime resolvedAt, @Param("resolvedBy") Long resolvedBy);

    @Update("UPDATE nf02_nf03_ownership_repair_queue SET assignee_user_id = #{assigneeUserId}, "
            + "last_checked_at = #{checkedAt} WHERE id = #{id} AND status = 'PENDING'")
    int assign(@Param("id") Long id, @Param("assigneeUserId") Long assigneeUserId,
               @Param("checkedAt") LocalDateTime checkedAt);
}
