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

    @Select("SELECT es.id, es.engineer_id, es.skill_id, es.proficiency, es.experience_years, " +
            "st.skill_name, st.category " +
            "FROM t_engineer_skill es JOIN t_engineer e ON e.id = es.engineer_id " +
            "JOIN m_skill_tag st ON es.skill_id = st.id " +
            "WHERE es.engineer_id = #{engineerId} AND e.tenant_id = #{tenantId} " +
            "AND e.deleted_flag = 0 ORDER BY st.category, st.skill_name")
    List<EngineerSkillDetailDto> selectDetailByEngineerIdAndTenant(@Param("engineerId") Long engineerId,
                                                                     @Param("tenantId") String tenantId);

    @Select("SELECT es.* FROM t_engineer_skill es JOIN t_engineer e ON e.id = es.engineer_id " +
            "WHERE es.engineer_id = #{engineerId} AND e.tenant_id = #{tenantId} " +
            "AND e.deleted_flag = 0 ORDER BY es.id")
    List<EngineerSkill> selectListByEngineerIdAndTenant(@Param("engineerId") Long engineerId,
                                                        @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM t_engineer_skill WHERE engineer_id = #{engineerId} " +
            "AND EXISTS (SELECT 1 FROM t_engineer e WHERE e.id = t_engineer_skill.engineer_id " +
            "AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0)")
    int deleteByEngineerIdAndTenant(@Param("engineerId") Long engineerId,
                                    @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Insert("INSERT INTO t_engineer_skill "
            + "(engineer_id, skill_id, proficiency, experience_years) "
            + "SELECT #{skill.engineerId}, #{skill.skillId}, #{skill.proficiency}, #{skill.experienceYears} "
            + "WHERE EXISTS (SELECT 1 FROM t_engineer e WHERE e.id = #{skill.engineerId} "
            + "AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0)")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "skill.id")
    int insertForTenant(@Param("skill") EngineerSkill skill, @Param("tenantId") String tenantId);

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

    @Select("""
        <script>
        SELECT es.engineer_id AS engineerId, st.skill_name AS skillName, es.proficiency AS proficiency
        FROM t_engineer_skill es
        JOIN t_engineer e ON e.id = es.engineer_id AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0
        JOIN m_skill_tag st ON es.skill_id = st.id
        WHERE es.engineer_id IN
        <foreach collection="engineerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY es.engineer_id, es.proficiency DESC, es.id ASC
        </script>
        """)
    List<EngineerSkillDetailDto> selectTopSkillCandidatesForTenant(@Param("engineerIds") List<Long> engineerIds,
                                                                     @Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT es.* FROM t_engineer_skill es JOIN t_engineer e ON e.id = es.engineer_id
        WHERE e.tenant_id = #{tenantId} AND e.deleted_flag = 0 AND es.engineer_id IN
        <foreach collection="engineerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY es.engineer_id, es.id
        </script>
        """)
    List<EngineerSkill> selectListByEngineerIdsAndTenant(@Param("engineerIds") List<Long> engineerIds,
                                                         @Param("tenantId") String tenantId);
}
