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

    @Select("SELECT d.* FROM t_lifecycle_task_dep d INNER JOIN t_lifecycle_case c ON c.id = d.case_id "
            + "WHERE d.case_id = #{caseId} AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0")
    List<LifecycleTaskDep> selectByCaseIdAndTenant(@Param("caseId") Long caseId, @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_lifecycle_task_dep WHERE successor_task_id = #{taskId}")
    List<LifecycleTaskDep> selectBySuccessorTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM t_lifecycle_task_dep WHERE predecessor_task_id = #{taskId}")
    List<LifecycleTaskDep> selectByPredecessorTaskId(@Param("taskId") Long taskId);

    @Select("SELECT d.* FROM t_lifecycle_task_dep d "
            + "INNER JOIN t_lifecycle_task t ON t.id = d.successor_task_id "
            + "INNER JOIN t_lifecycle_case c ON c.id = t.case_id "
            + "WHERE d.successor_task_id = #{taskId} AND c.tenant_id = #{tenantId} "
            + "AND c.deleted_flag = 0 AND t.deleted_flag = 0")
    List<LifecycleTaskDep> selectBySuccessorTaskIdAndTenant(@Param("taskId") Long taskId, @Param("tenantId") String tenantId);

    @Select("SELECT d.* FROM t_lifecycle_task_dep d "
            + "INNER JOIN t_lifecycle_task t ON t.id = d.predecessor_task_id "
            + "INNER JOIN t_lifecycle_case c ON c.id = t.case_id "
            + "WHERE d.predecessor_task_id = #{taskId} AND c.tenant_id = #{tenantId} "
            + "AND c.deleted_flag = 0 AND t.deleted_flag = 0")
    List<LifecycleTaskDep> selectByPredecessorTaskIdAndTenant(@Param("taskId") Long taskId, @Param("tenantId") String tenantId);
}
