package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_recommendation_item")
public class AiRecommendationItem extends BaseEntity {

    private Long runId;
    private Integer rankNo;
    private String targetType;
    private Long targetId;
    private BigDecimal score;
    private String explanationJson;
    private Integer selectedFlag;
}
