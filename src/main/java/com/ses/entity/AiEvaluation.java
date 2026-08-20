package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_evaluation")
public class AiEvaluation extends BaseEntity {

    private Long candidateVersionId;
    private Long baselineVersionId;
    private String datasetVersion;
    private String metricsJson;
    private String status;
    private Integer statusVersion;
    private Long approvedBy;
    private LocalDateTime approvedAt;
}
