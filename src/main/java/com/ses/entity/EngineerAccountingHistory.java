package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 要員の会計属性（原価部門・想定単価）の履歴。
 *
 * <p>Bench待機原価の月次snapshotは締めが遅れると要員の<b>現在の</b>値を読んでしまい、
 * 締め前の異動・単価改定が過去月へ焼き付く。対象月時点の版をここから解決する。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_engineer_accounting_history")
public class EngineerAccountingHistory extends BaseEntity {

    private Long engineerId;
    private Long costCenterId;
    private BigDecimal expectedUnitPrice;
    private LocalDate validFrom;
    /** NULL は現行版。 */
    private LocalDate validTo;
}
