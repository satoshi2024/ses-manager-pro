package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_outcome")
public class AiOutcome extends BaseEntity {

    private Long itemId;
    private String outcomeType;
    private String sourceType;
    private Long sourceId;
    private LocalDateTime occurredAt;
    private LocalDate originalEndDate;
    private String valueJson;
}
