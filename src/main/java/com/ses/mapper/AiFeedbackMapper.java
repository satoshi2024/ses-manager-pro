package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AiFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiFeedbackMapper extends BaseMapper<AiFeedback> {

    @Select("SELECT f.* FROM t_ai_feedback f "
            + "JOIN t_ai_recommendation_item i ON i.id = f.item_id "
            + "JOIN t_ai_recommendation_run r ON r.id = i.run_id "
            + "WHERE f.id = #{id} AND f.tenant_id = #{tenantId} "
            + "AND i.tenant_id = #{tenantId} AND r.tenant_id = #{tenantId} "
            + "AND f.deleted_flag = 0 AND i.deleted_flag = 0 AND r.deleted_flag = 0 LIMIT 1")
    AiFeedback selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);
}
