package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LifecycleCaseMapper extends BaseMapper<LifecycleCase> {

    @Select("SELECT * FROM t_lifecycle_case WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    LifecycleCase selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT MAX(case_no) FROM t_lifecycle_case WHERE case_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxCaseNoIncludingDeleted(@Param("prefix") String prefix);
}
