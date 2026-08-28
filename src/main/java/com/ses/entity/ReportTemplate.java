package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 管理レポートテンプレート（m_report_template）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_report_template")
public class ReportTemplate extends BaseEntity {

    private String tenantId;
    private String templateKey;
    private String templateName;
    private String status;
    private Long createdBy;
    private Long updatedBy;

    @Version
    private Integer version;
}
