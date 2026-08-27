package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * SLAポリシーマスタエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_service_sla_policy")
public class ServiceSlaPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String priority;

    private Integer responseTimeHours;

    private Integer resolveTimeHours;

    private LocalTime businessHoursStart;

    private LocalTime businessHoursEnd;

    private Boolean includeHolidays;

    private String status;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
