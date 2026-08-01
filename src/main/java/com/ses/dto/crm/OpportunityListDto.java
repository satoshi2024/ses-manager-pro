package com.ses.dto.crm;

import com.ses.entity.Opportunity;
import lombok.Data;

/** 商機一覧で顧客名を表示するためのDTO。 */
@Data
public class OpportunityListDto {
    private Long id;
    private Long customerId;
    private String customerName;
    private String title;
    private String stage;
    private String expectedStartMonth;
    private Integer durationMonths;
    private Integer requiredCount;
    private java.math.BigDecimal unitPrice;
    private java.math.BigDecimal expectedAmount;
    private Integer probability;
    private Long ownerUserId;
    private java.time.LocalDate nextActionDate;
    private String lostReason;
    private Long convertedProjectId;
    private Long convertedQuotationId;
    private Integer version;

    public static OpportunityListDto from(Opportunity source, String customerName) {
        OpportunityListDto dto = new OpportunityListDto();
        dto.id = source.getId();
        dto.customerId = source.getCustomerId();
        dto.customerName = customerName;
        dto.title = source.getTitle();
        dto.stage = source.getStage();
        dto.expectedStartMonth = source.getExpectedStartMonth();
        dto.durationMonths = source.getDurationMonths();
        dto.requiredCount = source.getRequiredCount();
        dto.unitPrice = source.getUnitPrice();
        dto.expectedAmount = source.getExpectedAmount();
        dto.probability = source.getProbability();
        dto.ownerUserId = source.getOwnerUserId();
        dto.nextActionDate = source.getNextActionDate();
        dto.lostReason = source.getLostReason();
        dto.convertedProjectId = source.getConvertedProjectId();
        dto.convertedQuotationId = source.getConvertedQuotationId();
        dto.version = source.getVersion();
        return dto;
    }
}
