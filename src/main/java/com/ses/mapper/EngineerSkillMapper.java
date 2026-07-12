package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.EngineerSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EngineerSkillMapper extends BaseMapper<EngineerSkill> {
    @Select("SELECT es.id, es.engineer_id, es.skill_id, es.proficiency, es.experience_years, " +
            "st.skill_name, st.category " +
            "FROM t_engineer_skill es JOIN m_skill_tag st ON es.skill_id = st.id " +
            "WHERE es.engineer_id = #{engineerId} ORDER BY st.category, st.skill_name")
    List<EngineerSkillDetailDto> selectDetailByEngineerId(Long engineerId);
}
