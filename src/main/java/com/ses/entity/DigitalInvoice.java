package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_digital_invoice")
public class DigitalInvoice {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long invoiceId;
    private String direction;
    private String profile;
    private String specificationVersion;
    private String messageId;
    private String providerMessageId;
    private Long xmlDocumentId;
    private Long validationDocumentId;
    private String status;
    private LocalDateTime sentAt;
    private LocalDateTime receivedAt;

    @Version
    private Long version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableLogic
    private Integer deletedFlag;
}
