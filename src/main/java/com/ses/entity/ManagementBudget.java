package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 月別の組織・原価部門予算エンティティ。金額は円単位。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_management_budget")
public class ManagementBudget extends BaseEntity {

    private Long organizationId;
    private Long costCenterId;
    private LocalDate budgetMonth;
    private BigDecimal revenue;
    private BigDecimal grossProfit;
    private Integer utilizationCount;
    private Integer hireCount;

    @Version
    private Integer version;
}
