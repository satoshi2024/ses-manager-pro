package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 月次確定時の帰属snapshot。金額は既存ソースを正とし、帰属だけを固定する。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_monthly_accounting_dimension")
public class MonthlyAccountingDimension implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate workMonth;
    private String sourceType;
    private Long sourceId;
    private Long organizationId;
    private Long costCenterId;
    private Long salesUserId;
    private BigDecimal revenue;
    private BigDecimal cost;
    private LocalDateTime snapshotAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
