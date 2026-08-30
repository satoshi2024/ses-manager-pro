package com.ses.entity.integrationhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** manual replayのsafe metadata。payloadやsecretは保存しない。 */
@Data
@TableName("t_api_delivery_replay_audit")
public class ApiDeliveryReplayAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deliveryId;
    private String eventId;
    private Integer replayGeneration;
    private String operatorRef;
    private String reasonCode;
    private String scopeDigest;
    private String payloadHash;
    private LocalDateTime createdAt;
}
