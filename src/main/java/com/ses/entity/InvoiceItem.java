package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("t_invoice_item")
public class InvoiceItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long invoiceId;
    private Long workRecordId;
    private String description;
    private BigDecimal amount;
}
