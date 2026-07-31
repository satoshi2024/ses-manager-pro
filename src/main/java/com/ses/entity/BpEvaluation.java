package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

/**
 * BP評価記録エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_bp_evaluation")
public class BpEvaluation extends BaseEntity {

    private Long tenantId;
    private Long bpCompanyId;
    private String period; // e.g. 2026-Q1
    private Integer qualityScore;
    private Integer responseScore;
    private Integer retentionScore;
    private Integer complianceScore;
    private Integer billingAccuracyScore;
    private String comment;
    private Long evaluatedBy;
}
