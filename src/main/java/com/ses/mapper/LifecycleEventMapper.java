package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleEventMapper extends BaseMapper<LifecycleEvent> {

    @Select("SELECT * FROM t_lifecycle_event WHERE case_id = #{caseId} ORDER BY occurred_at ASC, id ASC")
    List<LifecycleEvent> selectByCaseId(@Param("caseId") Long caseId);

    @Select("SELECT * FROM t_lifecycle_event WHERE task_id = #{taskId} ORDER BY occurred_at ASC, id ASC")
    List<LifecycleEvent> selectByTaskId(@Param("taskId") Long taskId);
}
