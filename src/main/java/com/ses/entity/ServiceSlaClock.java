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

/**
 * SLA計時クロックエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_sla_clock")
public class ServiceSlaClock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long serviceRequestId;

    private Integer roundNo;

    private Long policyId;

    private LocalDateTime responseDeadline;

    private LocalDateTime resolveDeadline;

    private LocalDateTime firstRespondedAt;

    private Boolean responseBreached;

    private LocalDateTime resolvedAt;

    private Boolean resolveBreached;

    private Integer totalPauseMinutes;

    private LocalDateTime lastPausedAt;

    private String status;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
