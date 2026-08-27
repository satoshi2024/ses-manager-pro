package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface LifecycleTemplateMapper extends BaseMapper<LifecycleTemplate> {

    @Select("SELECT * FROM m_lifecycle_template " +
            "WHERE template_type = #{type} AND status = 'ACTIVE' AND deleted_flag = 0 " +
            "AND valid_from <= #{asOf} AND (valid_to IS NULL OR valid_to >= #{asOf}) " +
            "ORDER BY version_no DESC LIMIT 1")
    LifecycleTemplate findActiveByTypeAndDate(@Param("type") String type, @Param("asOf") LocalDate asOf);

    @Select("SELECT MAX(version_no) FROM m_lifecycle_template WHERE template_type = #{type}")
    Integer selectMaxVersionNo(@Param("type") String type);
}
