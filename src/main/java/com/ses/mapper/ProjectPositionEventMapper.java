package com.ses.mapper;

import com.ses.entity.ProjectPositionEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
