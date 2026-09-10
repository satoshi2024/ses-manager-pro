package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AiRecommendationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiRecommendationRunMapper extends BaseMapper<AiRecommendationRun> {

    @Update("UPDATE t_ai_recommendation_run SET redacted_summary_json = NULL, updated_at = #{now} "
            + "WHERE id IN (SELECT id FROM (SELECT id FROM t_ai_recommendation_run "
            + "WHERE created_at < #{cutoff} AND redacted_summary_json IS NOT NULL AND deleted_flag = 0 "
            + "ORDER BY created_at, id LIMIT #{limit}) candidates)")
    int purgeExpiredSummaries(@Param("cutoff") LocalDateTime cutoff,
                              @Param("now") LocalDateTime now,
                              @Param("limit") int limit);
}
