package com.ses.mapper;

import com.ses.entity.ProjectSkillEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ProjectSkillEventMapper {

    @Insert("INSERT INTO t_project_skill_event "
            + "(tenant_id, project_id, project_skill_id, skill_id, required_level, is_must, event_type, "
            + "effective_from, effective_to, supersedes_event_id, actor_user_id, actor_role_snapshot, reason, "
            + "occurred_at, created_at) VALUES "
            + "(#{event.tenantId}, #{event.projectId}, #{event.projectSkillId}, #{event.skillId}, "
            + "#{event.requiredLevel}, #{event.isMust}, #{event.eventType}, #{event.effectiveFrom}, "
            + "#{event.effectiveTo}, #{event.supersedesEventId}, #{event.actorUserId}, #{event.actorRoleSnapshot}, "
            + "#{event.reason}, #{event.occurredAt}, #{event.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") ProjectSkillEvent event);

    @Select("SELECT * FROM t_project_skill_event WHERE project_id = #{projectId} ORDER BY occurred_at, id")
    List<ProjectSkillEvent> selectByProjectId(@Param("projectId") Long projectId);

    @Select("SELECT * FROM t_project_skill_event WHERE project_id = #{projectId} AND skill_id = #{skillId} "
            + "AND event_type = 'OPEN' AND effective_to IS NULL ORDER BY id DESC LIMIT 1")
    ProjectSkillEvent selectOpenEvent(@Param("projectId") Long projectId, @Param("skillId") Long skillId);

    @Update("UPDATE t_project_skill_event SET effective_to = #{effectiveTo} WHERE id = #{eventId} AND effective_to IS NULL")
    int closeOpenEvent(@Param("eventId") Long eventId, @Param("effectiveTo") LocalDate effectiveTo);

    @Select("SELECT * FROM t_project_skill_event WHERE project_id = #{projectId} AND skill_id = #{skillId} "
            + "AND event_type = 'OPEN' AND effective_to IS NOT NULL ORDER BY id DESC LIMIT 1")
    ProjectSkillEvent selectLastClosedOpenEvent(@Param("projectId") Long projectId, @Param("skillId") Long skillId);
}
