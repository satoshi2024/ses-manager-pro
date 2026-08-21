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
    private Long supplierCompanyId;
    private Long purchaseOrderId;
    private Long contractId;
    private String matchStatus;

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

    /**
     * MySQL 生成列 send_active_slot（V108.3）。INSERT/UPDATE から除外する。
     * 有効 SEND の UNIQUE(invoice_id, direction, profile, specification_version) 用スロット。
     */
    @TableField(exist = false)
    private Integer sendActiveSlot;
}

