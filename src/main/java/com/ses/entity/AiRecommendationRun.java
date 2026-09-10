package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_recommendation_run")
public class AiRecommendationRun extends BaseEntity {

    private String traceId;
    private String useCase;
    private Long artifactVersionId;
    private Long actorUserId;
    private String inputHash;
    private String redactedSummaryJson;
    private Integer latencyMs;
    private Integer tokenInput;
    private Integer tokenOutput;
    private Integer costJpy;
    private String status;
    private Integer statusVersion;
    private String errorCode;

    /** query開始時点の認可snapshot。legacy matchingも同じ境界を保存する。 */
    private String scopeHash;
    private String parameterHash;
    private String tenantId;
    private Long legalEntityId;
    private LocalDateTime asOfAt;
    private String timezoneId;
    private String catalogVersion;
    private String dataVersion;
}
