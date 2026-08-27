package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleTaskDep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleTaskDepMapper extends BaseMapper<LifecycleTaskDep> {

    @Select("SELECT * FROM t_lifecycle_task_dep WHERE case_id = #{caseId}")
    List<LifecycleTaskDep> selectByCaseId(@Param("caseId") Long caseId);

    @Select("SELECT * FROM t_lifecycle_task_dep WHERE successor_task_id = #{taskId}")
    List<LifecycleTaskDep> selectBySuccessorTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM t_lifecycle_task_dep WHERE predecessor_task_id = #{taskId}")
    List<LifecycleTaskDep> selectByPredecessorTaskId(@Param("taskId") Long taskId);
}
