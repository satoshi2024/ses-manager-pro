package com.ses.dto.proposal;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 提案かんばんDTO
 */
@Data
public class ProposalKanbanDto {
    private Long id;
    private Long engineerId;
    private Long projectId;
    private String engineerName;
    private String engineerInitial;
    private String projectName;
    private String customerName;
    private BigDecimal proposedUnitPrice;
    private String status;
    private BigDecimal aiMatchScore;
    private java.time.LocalDateTime proposedAt;
}
