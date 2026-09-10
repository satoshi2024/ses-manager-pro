package com.ses.dto.candidate;

import com.ses.entity.Candidate;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 候補者APIの安全な表示DTO。tenantと内部監査列は含めない。 */
@Data
public class CandidateResponse {
    private Long id;
    private String name;
    private String contactEmail;
    private String contactPhone;
    private String skillSummary;
    private BigDecimal desiredRate;
    private String source;
    private String currentStage;
    private LocalDate nextActionDate;
    private Long convertedEngineerId;
    private Integer version;
    private String remarks;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CandidateResponse from(Candidate source) {
        if (source == null) {
            return null;
        }
        CandidateResponse target = new CandidateResponse();
        target.id = source.getId();
        target.name = source.getName();
        target.contactEmail = source.getContactEmail();
        target.contactPhone = source.getContactPhone();
        target.skillSummary = source.getSkillSummary();
        target.desiredRate = source.getDesiredRate();
        target.source = source.getSource();
        target.currentStage = source.getCurrentStage();
        target.nextActionDate = source.getNextActionDate();
        target.convertedEngineerId = source.getConvertedEngineerId();
        target.version = source.getVersion();
        target.remarks = source.getRemarks();
        target.createdAt = source.getCreatedAt();
        target.updatedAt = source.getUpdatedAt();
        return target;
    }
}
