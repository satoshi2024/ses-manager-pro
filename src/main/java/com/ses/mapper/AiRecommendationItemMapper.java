package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AiRecommendationItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiRecommendationItemMapper extends BaseMapper<AiRecommendationItem> {

    @Select("SELECT i.* FROM t_ai_recommendation_item i "
            + "JOIN t_ai_recommendation_run r ON r.id = i.run_id "
            + "WHERE i.id = #{id} AND i.tenant_id = #{tenantId} "
            + "AND r.tenant_id = #{tenantId} AND i.deleted_flag = 0 AND r.deleted_flag = 0 LIMIT 1")
    AiRecommendationItem selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Update("UPDATE t_ai_recommendation_item SET selected_flag = #{selectedFlag}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND selected_flag <> #{selectedFlag} AND deleted_flag = 0")
    int updateSelectedByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                                    @Param("selectedFlag") Integer selectedFlag);
}
