package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.entity.ProjectSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectSkillMapper extends BaseMapper<ProjectSkill> {
    @Select("SELECT ps.id, ps.project_id, ps.skill_id, ps.required_level, ps.is_must, " +
            "st.skill_name, st.category " +
            "FROM t_project_skill ps JOIN m_skill_tag st ON ps.skill_id = st.id " +
            "WHERE ps.project_id = #{projectId} ORDER BY ps.is_must DESC, st.skill_name")
    List<ProjectSkillDetailDto> selectDetailByProjectId(Long projectId);
}
