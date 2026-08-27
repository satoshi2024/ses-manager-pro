package com.ses.dto.report;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 管理レポートtemplate作成要求。 */
@Data
public class ReportTemplateCreateRequest {
    @NotBlank
    private String templateKey;
    @NotBlank
    private String templateName;
}
