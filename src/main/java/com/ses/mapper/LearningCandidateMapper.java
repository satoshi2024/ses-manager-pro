package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LearningCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LearningCandidateMapper extends BaseMapper<LearningCandidate> {

    @Select("SELECT * FROM t_learning_candidate WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 LIMIT 1")
    LearningCandidate selectByTenantId(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Update("UPDATE t_learning_candidate SET status = #{status}, decision_actor_user_id = #{actorUserId}, "
            + "decision_reason = #{reason}, decided_at = #{decidedAt}, updated_at = #{decidedAt} "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'PENDING' AND deleted_flag = 0")
    int decide(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status,
               @Param("actorUserId") Long actorUserId, @Param("reason") String reason,
               @Param("decidedAt") java.time.LocalDateTime decidedAt);
}
