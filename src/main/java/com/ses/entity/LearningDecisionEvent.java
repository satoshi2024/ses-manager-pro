package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_learning_decision_event")
public class LearningDecisionEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private String decisionDomain;
    private String sourceType;
    private Long sourceId;
    private Long humanActorUserId;
    private Integer adverseUseFlag;
    private String reason;
    private String snapshotHash;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
