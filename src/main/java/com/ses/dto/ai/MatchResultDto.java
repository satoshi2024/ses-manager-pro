package com.ses.dto.ai;

import lombok.Data;

/**
 * AIマッチング結果DTO
 */
@Data
public class MatchResultDto {
    private Long projectId;
    private String projectName;
    private Integer score;
    private String reason;
    private String sellingPoints;
    
    // 逆方向マッチング（案件→要員）用
    private Long engineerId;
    private String engineerName;
    private Integer proposedPrice;
    
    // 外部要員（BP）の場合
    private Long bpAvailabilityId;
    private Boolean isExternalBp;
}
