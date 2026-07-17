package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 請求書入金。物理行（deleted_flag なし。取消=削除、監査は操作ログで担保）。
 */
@Data
@TableName("t_invoice_payment")
public class InvoicePayment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long invoiceId;
    private LocalDate paidDate;
    private BigDecimal amount;
    /** 振込手数料(円・当方負担の目減り)。消込判定は amount+fee で行う。 */
    private BigDecimal fee;
    private String remarks;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
