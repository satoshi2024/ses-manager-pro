package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.integrationhub.ApiDelivery;

import java.time.LocalDateTime;

/** NF-05 dedicated delivery ledger service。外部HTTPはこのservice transaction外のworkerで行う。 */
public interface ApiDeliveryService extends IService<ApiDelivery> {
    ApiDelivery enqueue(String eventId, Long subscriptionId, int generation, String clientId, String scopeCode,
                        String tenantId, String eventType, String schemaVersion, String correlationId,
                        ExternalDtoSnapshot snapshot, LocalDateTime now);

    ApiDelivery claim(Long id, String leaseToken, LocalDateTime now, LocalDateTime leaseExpiresAt);

    boolean markSucceeded(Long id, Integer version, String leaseToken, String payloadHash,
                          String providerRequestId, LocalDateTime now);

    boolean markRetryable(Long id, Integer version, String leaseToken, String payloadHash,
                          String errorCode, LocalDateTime now, LocalDateTime nextAttemptAt);

    boolean markTerminal(Long id, Integer version, String leaseToken, String payloadHash,
                         String status, String errorCode, LocalDateTime now);

    int recoverExpiredLeases(LocalDateTime now);
}
