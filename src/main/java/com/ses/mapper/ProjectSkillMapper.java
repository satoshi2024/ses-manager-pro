package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.entity.ProjectSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectSkillMapper extends BaseMapper<ProjectSkill> {
    /** 案件のtenant ownershipを親顧客で解決した詳細一覧。 */
    @Select("SELECT ps.id, ps.project_id, ps.skill_id, ps.required_level, ps.is_must, "
            + "st.skill_name, st.category "
            + "FROM t_project_skill ps "
            + "JOIN t_project p ON p.id = ps.project_id AND p.deleted_flag = 0 "
            + "JOIN m_customer c ON c.id = p.customer_id AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_skill_tag st ON ps.skill_id = st.id "
            + "WHERE ps.project_id = #{projectId} ORDER BY ps.is_must DESC, st.skill_name")
    List<ProjectSkillDetailDto> selectDetailByProjectIdForTenant(@Param("projectId") Long projectId,
                                                                  @Param("tenantId") String tenantId);

    /** 案件の現行skill projectionをtenant ownership付きで取得する。 */
    @Select("SELECT ps.* FROM t_project_skill ps "
            + "JOIN t_project p ON p.id = ps.project_id AND p.deleted_flag = 0 "
            + "JOIN m_customer c ON c.id = p.customer_id AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE ps.project_id = #{projectId} ORDER BY ps.id")
    List<ProjectSkill> selectByProjectIdForTenant(@Param("projectId") Long projectId,
                                                  @Param("tenantId") String tenantId);

    /** 案件単位のreplaceをtenant ownership付きで行う。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_project_skill "
            + "WHERE project_id = #{projectId} "
            + "AND EXISTS (SELECT 1 FROM t_project p JOIN m_customer c ON c.id = p.customer_id "
            + "WHERE p.id = t_project_skill.project_id AND p.deleted_flag = 0 "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0)")
    int deleteByProjectIdForTenant(@Param("projectId") Long projectId, @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Insert("INSERT INTO t_project_skill "
            + "(project_id, skill_id, required_level, is_must) "
            + "SELECT #{skill.projectId}, #{skill.skillId}, #{skill.requiredLevel}, #{skill.isMust} "
            + "WHERE EXISTS (SELECT 1 FROM t_project p JOIN m_customer c ON c.id = p.customer_id "
            + "WHERE p.id = #{skill.projectId} AND c.tenant_id = #{tenantId} "
            + "AND p.deleted_flag = 0 AND c.deleted_flag = 0)")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "skill.id")
    int insertForTenant(@Param("skill") ProjectSkill skill, @Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT ps.* FROM t_project_skill ps JOIN t_project p ON p.id = ps.project_id
        JOIN m_customer c ON c.id = p.customer_id
        WHERE c.tenant_id = #{tenantId} AND p.deleted_flag = 0 AND c.deleted_flag = 0
          AND ps.project_id IN
        <foreach collection="projectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY ps.project_id, ps.id
        </script>
        """)
    List<ProjectSkill> selectListForTenant(@Param("projectIds") List<Long> projectIds,
                                           @Param("tenantId") String tenantId);
}
