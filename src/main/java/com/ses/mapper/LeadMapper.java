package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Lead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LeadMapper extends BaseMapper<Lead> {

    @Select("SELECT * FROM t_lead WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Lead selectByIdForUpdate(@Param("id") Long id);
}
