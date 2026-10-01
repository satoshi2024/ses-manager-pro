package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_digital_invoice_event")
public class DigitalInvoiceEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 親デジタルインボイスから固定するテナント境界。 */
    private String tenantId;

    /** 親デジタルインボイスから固定する法人境界。 */
    private Long legalEntityId;

    private Long digitalInvoiceId;
    private String providerEventId;
    private String eventType;
    private LocalDateTime eventAt;
    private String payloadHash;
    private String canonicalPayloadHash;
    private Boolean signatureValid;

    private String actorType;
    private String confirmationSource;
    private Long humanUserId;
    private String correlationId;
    private String idempotencyKey;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
