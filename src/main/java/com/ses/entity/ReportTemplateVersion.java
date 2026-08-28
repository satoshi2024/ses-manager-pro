package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 管理レポートテンプレート版（m_report_template_version）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_report_template_version")
public class ReportTemplateVersion extends BaseEntity {

    private String tenantId;
    private Long templateId;
    private Integer versionNo;
    private String status;
    private String sectionConfigJson;
    private String formatConfigJson;
    private String recipientConfigJson;
    private String scopeConfigJson;
    private String timezoneId;
    private Integer retentionYears;
    private Long createdBy;
    private LocalDateTime publishedAt;

    @Version
    private Integer version;
}
