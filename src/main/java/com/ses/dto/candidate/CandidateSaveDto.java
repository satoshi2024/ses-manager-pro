package com.ses.dto.candidate;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CandidateSaveDto {
    private Long id;

    @NotBlank(message = "氏名は必須です")
    private String name;

    private String contactEmail;
    private String contactPhone;
    private String skillSummary;
    private BigDecimal desiredRate;
    private String source;
    private String currentStage;
    private LocalDate nextActionDate;
    private Long convertedEngineerId;
    private String remarks;
}
