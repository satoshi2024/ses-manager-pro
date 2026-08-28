package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_project_position_event")
public class ProjectPositionEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long positionId;
    private Long projectId;
    private String eventType;
    private String positionNo;
    private String roleName;
    private Integer requiredCount;
    private String skillsJson;
    private BigDecimal unitPriceMin;
    private BigDecimal unitPriceMax;
    private LocalDate startDate;
    private LocalDate endDate;
    private String location;
    private BigDecimal allocationPercent;
    private String priority;
    private String status;
    private Integer sourceVersion;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Long actorUserId;
    private String actorRoleSnapshot;
    private String reason;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;

    public static final String TYPE_CREATE = "CREATE";
    public static final String TYPE_UPDATE = "UPDATE";
    public static final String TYPE_STATUS_CHANGE = "STATUS_CHANGE";
    public static final String TYPE_DELETE = "DELETE";
}
