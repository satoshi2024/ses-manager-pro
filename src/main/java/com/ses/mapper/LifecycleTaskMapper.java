package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleTaskMapper extends BaseMapper<LifecycleTask> {

    @Select("SELECT * FROM t_lifecycle_task WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    LifecycleTask selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_lifecycle_task WHERE case_id = #{caseId} AND deleted_flag = 0 ORDER BY id ASC")
    List<LifecycleTask> selectByCaseId(@Param("caseId") Long caseId);

    @Select("SELECT * FROM t_lifecycle_task WHERE case_id = #{caseId} AND is_engineer_visible = 1 AND deleted_flag = 0 ORDER BY id ASC")
    List<LifecycleTask> selectEngineerVisibleByCaseId(@Param("caseId") Long caseId);
}
