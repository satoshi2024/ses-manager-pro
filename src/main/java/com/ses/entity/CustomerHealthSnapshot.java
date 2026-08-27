package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 顧客ヘルススナップショットエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_health_snapshot")
public class CustomerHealthSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    private LocalDate snapshotDate;

    private String healthStatus;

    private Integer totalScore;

    private Integer openCriticalIssuesCount;

    @com.baomidou.mybatisplus.annotation.TableField("sla_breach_count_30d")
    private Integer slaBreachCount30d;

    private BigDecimal avgCsatScore;

    private Boolean arOverdueFlag;

    private String missingInputsJson;

    private String factorsExplanation;

    private LocalDateTime createdAt;
}
