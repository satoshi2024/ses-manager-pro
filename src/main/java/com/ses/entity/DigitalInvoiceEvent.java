package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_digital_invoice_event")
public class DigitalInvoiceEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long digitalInvoiceId;
    private String providerEventId;
    private String eventType;
    private LocalDateTime eventAt;
    private String payloadHash;
    private Boolean signatureValid;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
