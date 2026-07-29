package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_break_glass_incident")
public class BreakGlassIncident extends BaseEntity {
    private String tenantId;
    private String incidentKey;
    private String status;
    private String reason;
    private Integer idpOutageConfirmed;
    private String correlationId;
    private Long requestedBy;
    @TableField("approved_by_1")
    private Long approvedBy1;
    @TableField("approved_at_1")
    private LocalDateTime approvedAt1;
    @TableField("approved_by_2")
    private Long approvedBy2;
    @TableField("approved_at_2")
    private LocalDateTime approvedAt2;
    private LocalDateTime enabledFrom;
    private LocalDateTime enabledUntil;
    private Long closedBy;
    private LocalDateTime closedAt;
}
