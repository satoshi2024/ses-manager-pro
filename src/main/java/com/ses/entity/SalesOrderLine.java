package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 注文明細エンティティ（t_sales_order_line）。
 * 1要員1明細を初期単位とし、複数要員注文を複数明細で表現する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sales_order_line")
public class SalesOrderLine extends BaseEntity {

    /** 注文ID */
    @NotNull(message = "注文は必須です")
    private Long orderId;

    /** 明細番号 */
    private Integer lineNo;

    /** 案件ID */
    private Long projectId;

    /** 要員ID */
    @NotNull(message = "要員は必須です")
    private Long engineerId;

    /** 数量 */
    private Integer quantity;

    /** 単価(円/月) */
    @NotNull(message = "単価は必須です")
    private BigDecimal unitPrice;

    /** 精算下限(h) */
    private BigDecimal settlementMin;

    /** 精算上限(h) */
    private BigDecimal settlementMax;

    /** 明細金額(円) */
    private BigDecimal amount;

    /** 備考 */
    private String remarks;
}
