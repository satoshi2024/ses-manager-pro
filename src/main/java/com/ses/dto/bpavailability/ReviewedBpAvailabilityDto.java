package com.ses.dto.bpavailability;

import lombok.Data;
import java.util.List;

@Data
public class ReviewedBpAvailabilityDto {
    private String initialName;
    private String bpCompany;
    /** 人による候補確定後のBP会社ID（自由入力の新規禁止: R2.2/R2.4） */
    private Long bpCompanyId;
    private List<String> skills;
    private Long unitPrice;
    private String availableFrom; // yyyy-MM-dd
    private Integer experienceYears;
    private String remarks;
    private String reviewNote;
}
