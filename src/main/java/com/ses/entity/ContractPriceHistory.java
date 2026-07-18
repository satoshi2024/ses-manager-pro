package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 契約単価改定履歴。期間別単価（apply_from_month 以降に有効）。
 */
@Data
@TableName("t_contract_price_history")
public class ContractPriceHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long contractId;
    /** 適用開始月(YYYY-MM)。 */
    private String applyFromMonth;
    private BigDecimal sellingPrice;
    private BigDecimal costPrice;
    private String reason;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
