package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.EngineerSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

import org.apache.ibatis.annotations.Param;

@Mapper
public interface EngineerSkillMapper extends BaseMapper<EngineerSkill> {
    @Select("SELECT es.id, es.engineer_id, es.skill_id, es.proficiency, es.experience_years, " +
            "st.skill_name, st.category " +
            "FROM t_engineer_skill es JOIN m_skill_tag st ON es.skill_id = st.id " +
            "WHERE es.engineer_id = #{engineerId} ORDER BY st.category, st.skill_name")
    List<EngineerSkillDetailDto> selectDetailByEngineerId(Long engineerId);

    @Select("""
        <script>
        SELECT es.engineer_id AS engineerId, st.skill_name AS skillName, es.proficiency AS proficiency
        FROM t_engineer_skill es
        JOIN m_skill_tag st ON es.skill_id = st.id
        WHERE es.engineer_id IN
        <foreach collection="engineerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY es.engineer_id, es.proficiency DESC, es.id ASC
        </script>
        """)
    List<EngineerSkillDetailDto> selectTopSkillCandidates(@Param("engineerIds") List<Long> engineerIds);
}
