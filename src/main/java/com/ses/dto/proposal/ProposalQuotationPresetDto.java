package com.ses.dto.proposal;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class ProposalQuotationPresetDto {
    private Long id;
    private Long engineerId;
    private Long projectId;
    private Long customerId;
    private BigDecimal proposedUnitPrice;
    private String projectName;
}
