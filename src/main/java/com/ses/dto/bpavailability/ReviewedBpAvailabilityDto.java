package com.ses.dto.bpavailability;

import lombok.Data;
import java.util.List;

@Data
public class ReviewedBpAvailabilityDto {
    private String initialName;
    private String bpCompany;
    private List<String> skills;
    private Long unitPrice;
    private String availableFrom; // yyyy-MM-dd
    private Integer experienceYears;
    private String remarks;
    private String reviewNote;
}
