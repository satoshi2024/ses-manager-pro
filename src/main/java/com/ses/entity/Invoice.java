package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
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
    private Long costCenterId;
    private String status;
    private LocalDate issuedDate;
    private LocalDate paidDate;
    private LocalDate dueDate;
    /** 受領確認日時（顧客portalが一度だけ設定。R2.3） */
    private LocalDateTime receivedConfirmedAt;
    /** 支払予定日（顧客portalが登録。R2.3） */
    private LocalDate paymentExpectedDate;
    /** 請求に関する問い合わせ（顧客portalが登録。R2.3） */
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String portalInquiry;
    private String remarks;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Integer version;
    
    @TableLogic
    private Integer deletedFlag;
}
