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

/** NF-05専用delivery ledger。既存notification outbox/会計jobとは別責務。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_api_delivery")
public class ApiDelivery {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long subscriptionId;
    private Integer deliveryGeneration;
    private String clientId;
    private String scopeCode;
    private String tenantId;
    private String scopeDigest;
    private String eventType;
    private String schemaVersion;
    private String correlationId;
    private String providerIdempotencyKey;
    private String externalDtoSnapshot;
    private String payloadHash;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptAt;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private String providerRequestId;
    private String lastErrorCode;
    private LocalDateTime terminalAt;
    private String retentionClass;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
