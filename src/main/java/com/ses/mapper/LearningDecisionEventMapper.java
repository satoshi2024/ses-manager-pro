package com.ses.mapper;

import com.ses.entity.LearningDecisionEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LearningDecisionEventMapper {

    @Insert("INSERT INTO t_learning_decision_event "
            + "(tenant_id, decision_domain, source_type, source_id, human_actor_user_id, adverse_use_flag, "
            + "reason, snapshot_hash, occurred_at, created_at) VALUES "
            + "(#{event.tenantId}, #{event.decisionDomain}, #{event.sourceType}, #{event.sourceId}, "
            + "#{event.humanActorUserId}, #{event.adverseUseFlag}, #{event.reason}, #{event.snapshotHash}, "
            + "#{event.occurredAt}, #{event.createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insertEvent(@Param("event") LearningDecisionEvent event);
}
