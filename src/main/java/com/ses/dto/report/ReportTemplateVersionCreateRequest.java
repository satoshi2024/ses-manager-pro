package com.ses.dto.report;

import lombok.Data;

/** 管理レポートtemplate version作成要求。JSONはversionへそのまま固定する。 */
@Data
public class ReportTemplateVersionCreateRequest {
    private String sectionConfigJson;
    private String formatConfigJson;
    private String recipientConfigJson;
    private String scopeConfigJson;
    private String timezoneId;
    private Integer retentionYears;
}
