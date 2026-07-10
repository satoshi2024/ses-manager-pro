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
}
