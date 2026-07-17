package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_invoice")
public class Invoice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String invoiceNo;
    private Long customerId;
    private String billingMonth;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;
    /** 生成時点の適用税率(小数。例: 0.10)。既存行はNULL=設定値へフォールバック。 */
    private BigDecimal taxRate;
    private String status;
    private LocalDate issuedDate;
    private LocalDate paidDate;
    private LocalDate dueDate;
    private String remarks;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @TableLogic
    private Integer deletedFlag;
}
