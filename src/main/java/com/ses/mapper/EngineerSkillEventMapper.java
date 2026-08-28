package com.ses.mapper;

import com.ses.entity.EngineerSkillEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EngineerSkillEventMapper {

    @Insert("INSERT INTO t_engineer_skill_event "
            + "(tenant_id, engineer_id, engineer_skill_id, skill_id, proficiency, experience_years, event_type, "
            + "effective_from, effective_to, supersedes_event_id, actor_user_id, actor_role_snapshot, reason, "
            + "occurred_at, created_at) VALUES "
            + "(#{event.tenantId}, #{event.engineerId}, #{event.engineerSkillId}, #{event.skillId}, "
            + "#{event.proficiency}, #{event.experienceYears}, #{event.eventType}, #{event.effectiveFrom}, "
            + "#{event.effectiveTo}, #{event.supersedesEventId}, #{event.actorUserId}, #{event.actorRoleSnapshot}, "
            + "#{event.reason}, #{event.occurredAt}, #{event.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") EngineerSkillEvent event);

    @Select("SELECT * FROM t_engineer_skill_event WHERE engineer_id = #{engineerId} ORDER BY occurred_at, id")
    List<EngineerSkillEvent> selectByEngineerId(@Param("engineerId") Long engineerId);
}
