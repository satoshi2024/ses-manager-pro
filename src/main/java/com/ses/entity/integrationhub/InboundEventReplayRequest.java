package com.ses.entity.integrationhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** inbound DLQ replayのmetadata ledger。payload/raw bodyは複製しない。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_inbound_event_replay")
public class InboundEventReplayRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String replayReference;
    private Long inboundEventId;
    private String clientId;
    private String providerName;
    private String providerEventId;
    private String rawBodyHash;
    private Integer replayGeneration;
    private String operatorRef;
    private String reasonCode;
    private String status;
    private String resultCode;
    private String retentionClass;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
