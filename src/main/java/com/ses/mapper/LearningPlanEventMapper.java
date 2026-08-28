package com.ses.mapper;

import com.ses.entity.LearningPlanEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** learning plan/enrollment eventはINSERT/SELECTのみ。 */
@Mapper
public interface LearningPlanEventMapper {

    @Insert("INSERT INTO t_learning_plan_event "
            + "(tenant_id, plan_id, source_type, source_id, event_type, amount_snapshot, actor_user_id, reason, "
            + "occurred_at, idempotency_key, created_at) VALUES "
            + "(#{event.tenantId}, #{event.planId}, #{event.sourceType}, #{event.sourceId}, #{event.eventType}, "
            + "#{event.amountSnapshot}, #{event.actorUserId}, #{event.reason}, #{event.occurredAt}, "
            + "#{event.idempotencyKey}, #{event.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") LearningPlanEvent event);

    @Select("SELECT * FROM t_learning_plan_event WHERE plan_id = #{planId} ORDER BY occurred_at, id")
    List<LearningPlanEvent> selectByPlanId(@Param("planId") Long planId);

    @Select("SELECT * FROM t_learning_plan_event WHERE tenant_id = #{tenantId} AND idempotency_key = #{idempotencyKey} LIMIT 1")
    LearningPlanEvent selectByIdempotencyKey(@Param("tenantId") String tenantId,
                                             @Param("idempotencyKey") String idempotencyKey);
}
