package com.ses.dto.engineer;

import com.ses.entity.Engineer;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EngineerListDto extends Engineer {
    private Long primarySalesUserId;
    private String primarySalesUserName;

    /** 定着リスクスコア（0-100、RetentionRiskService） */
    private Integer retentionRiskScore;

    /** 定着リスク高判定 */
    private Boolean retentionHighRisk;
}
