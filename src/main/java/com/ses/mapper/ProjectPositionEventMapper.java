package com.ses.mapper;

import com.ses.entity.ProjectPositionEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ProjectPositionEventMapper {

    @Insert("INSERT INTO t_project_position_event "
            + "(tenant_id, position_id, project_id, event_type, position_no, role_name, required_count, skills_json, "
            + "unit_price_min, unit_price_max, start_date, end_date, location, allocation_percent, priority, status, "
            + "source_version, effective_from, effective_to, actor_user_id, actor_role_snapshot, reason, "
            + "occurred_at, created_at) VALUES "
            + "(#{event.tenantId}, #{event.positionId}, #{event.projectId}, #{event.eventType}, #{event.positionNo}, "
            + "#{event.roleName}, #{event.requiredCount}, #{event.skillsJson}, #{event.unitPriceMin}, "
            + "#{event.unitPriceMax}, #{event.startDate}, #{event.endDate}, #{event.location}, "
            + "#{event.allocationPercent}, #{event.priority}, #{event.status}, #{event.sourceVersion}, "
            + "#{event.effectiveFrom}, #{event.effectiveTo}, #{event.actorUserId}, #{event.actorRoleSnapshot}, "
            + "#{event.reason}, #{event.occurredAt}, #{event.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") ProjectPositionEvent event);

    @Select("SELECT * FROM t_project_position_event WHERE position_id = #{positionId} ORDER BY occurred_at, id")
    List<ProjectPositionEvent> selectByPositionId(@Param("positionId") Long positionId);

    @Select("SELECT * FROM t_project_position_event WHERE project_id = #{projectId} ORDER BY position_id, occurred_at, id")
    List<ProjectPositionEvent> selectByProjectId(@Param("projectId") Long projectId);

    @Select("SELECT * FROM t_project_position_event WHERE position_id = #{positionId} AND effective_to IS NULL "
            + "ORDER BY id DESC LIMIT 1")
    ProjectPositionEvent selectOpenEvent(@Param("positionId") Long positionId);

    @Update("UPDATE t_project_position_event SET effective_to = #{effectiveTo} WHERE id = #{eventId} AND effective_to IS NULL")
    int closeOpenEvent(@Param("eventId") Long eventId, @Param("effectiveTo") LocalDate effectiveTo);
}
