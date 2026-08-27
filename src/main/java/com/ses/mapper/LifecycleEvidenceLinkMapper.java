package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleEvidenceLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LifecycleEvidenceLinkMapper extends BaseMapper<LifecycleEvidenceLink> {

    @Select("SELECT * FROM t_lifecycle_evidence_link WHERE task_id = #{taskId}")
    List<LifecycleEvidenceLink> selectByTaskId(@Param("taskId") Long taskId);
}
