package com.ses.dto.report;

import lombok.Data;

import java.time.LocalDateTime;

/** 月次管理レポートschedule作成要求。timezoneはAsia/Tokyoに固定する。 */
@Data
public class ReportScheduleCreateRequest {
    private Long templateVersionId;
    private String cronExpression;
    private LocalDateTime nextRunAt;
}
