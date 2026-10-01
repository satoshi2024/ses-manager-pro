package com.ses.dto.projectingestion;

import com.ses.entity.ProjectIngestion;
import lombok.Data;

import java.time.LocalDateTime;

/** 案件メール取込の安全な表示DTO。tenant・登録者・storage keyは返さない。 */
@Data
public class ProjectIngestionResponse {

    private Long id;
    private String sourceType;
    private String originalFileName;
    private String status;
    private String rawText;
    private String parsedJson;
    private String aiProvider;
    private String aiModel;
    private String errorMessage;
    private Long convertedProjectId;
    private String reviewNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer version;

    public static ProjectIngestionResponse from(ProjectIngestion source) {
        if (source == null) {
            return null;
        }
        ProjectIngestionResponse target = new ProjectIngestionResponse();
        target.id = source.getId();
        target.sourceType = source.getSourceType();
        target.originalFileName = source.getOriginalFileName();
        target.status = source.getStatus();
        target.rawText = source.getRawText();
        target.parsedJson = source.getParsedJson();
        target.aiProvider = source.getAiProvider();
        target.aiModel = source.getAiModel();
        target.errorMessage = source.getErrorMessage();
        target.convertedProjectId = source.getConvertedProjectId();
        target.reviewNote = source.getReviewNote();
        target.createdAt = source.getCreatedAt();
        target.updatedAt = source.getUpdatedAt();
        target.version = source.getVersion();
        return target;
    }
}
