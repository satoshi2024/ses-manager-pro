package com.ses.dto.customer;

import lombok.Data;

@Data
public class CustomerSummaryDto {
    private Long projectCount;
    private Long proposalCount;
    private Long wonCount;
    private Double winRate;
    private Long activeContractCount;
    private Long pendingFollowUpCount;
}
