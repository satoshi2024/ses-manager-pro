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

/** NF-05 inbound replay ledger。raw bodyではなくhashとallow-list parsed fieldsのみ。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_inbound_event")
public class InboundEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientId;
    private String providerName;
    private String providerEventId;
    private String rawBodyHash;
    private LocalDateTime signedTimestamp;
    private String parsedFieldsSnapshot;
    private Boolean signatureValid;
    private String status;
    private String resultCode;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private LocalDateTime terminalAt;
    private String retentionClass;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
