package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** learning plan/enrollmentのappend-only監査event。 */
@Data
@TableName("t_learning_plan_event")
public class LearningPlanEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long planId;
    private String sourceType;
    private Long sourceId;
    private String eventType;
    private BigDecimal amountSnapshot;
    private Long actorUserId;
    private String reason;
    private LocalDateTime occurredAt;
    private String idempotencyKey;
    private LocalDateTime createdAt;
}
