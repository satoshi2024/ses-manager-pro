package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_feedback")
public class AiFeedback extends BaseEntity {

    private Long itemId;
    private String decision;
    private String reasonCode;
    private String commentRedacted;
    private Long decidedBy;
    private LocalDateTime decidedAt;
}
