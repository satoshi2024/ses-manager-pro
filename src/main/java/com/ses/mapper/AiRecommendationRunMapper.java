package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AiRecommendationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiRecommendationRunMapper extends BaseMapper<AiRecommendationRun> {

    @Select("SELECT * FROM t_ai_recommendation_run WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0 LIMIT 1")
    AiRecommendationRun selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /** tenant_idは必須。NULL分岐による全tenant掃除は禁止。 */
    @Update("UPDATE t_ai_recommendation_run SET redacted_summary_json = NULL, updated_at = #{now} "
            + "WHERE id IN (SELECT id FROM (SELECT id FROM t_ai_recommendation_run "
            + "WHERE created_at < #{cutoff} AND redacted_summary_json IS NOT NULL AND deleted_flag = 0 "
            + "AND tenant_id = #{tenantId} "
            + "ORDER BY created_at, id LIMIT #{limit}) candidates)")
    int purgeExpiredSummaries(@Param("tenantId") String tenantId,
                              @Param("cutoff") LocalDateTime cutoff,
                              @Param("now") LocalDateTime now,
                              @Param("limit") int limit);

    default int purgeExpiredSummaries(String tenantId, LocalDateTime cutoff, LocalDateTime now) {
        return purgeExpiredSummaries(tenantId, cutoff, now, 1000);
    }
}
