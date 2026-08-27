package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 管理レポートschedule（m_report_schedule）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_report_schedule")
public class ReportSchedule extends BaseEntity {

    private String tenantId;
    private Long templateVersionId;
    private String cronExpression;
    private String timezoneId;
    private Integer enabled;
    private String lockKey;
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private String scopeOwnerType;
    private Long scopeOwnerId;
    private String organizationScopeJson;
    private String scopePolicyVersion;
    private String scopeHash;
    private LocalDateTime retryScheduledAt;
    private Integer failureCount;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
