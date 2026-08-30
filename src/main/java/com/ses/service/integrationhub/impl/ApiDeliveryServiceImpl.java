package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.service.integrationhub.ApiDeliveryService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubStates;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** NF-05 dedicated delivery ledger implementation。 */
@Service
@RequiredArgsConstructor
public class ApiDeliveryServiceImpl implements ApiDeliveryService {
    private static final int MAX_ATTEMPTS = 8;
    private final ApiDeliveryMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiDelivery enqueue(String eventId, Long subscriptionId, int generation, String clientId, String scopeCode,
                               String tenantId, String eventType, String schemaVersion, String correlationId,
                               ExternalDtoSnapshot snapshot, LocalDateTime now) {
        requireText(eventId, 128, "eventId");
        requireText(clientId, 100, "clientId");
        requireText(scopeCode, 100, "scopeCode");
        requireText(tenantId, 64, "tenantId");
        requireText(eventType, 100, "eventType");
        requireText(schemaVersion, 32, "schemaVersion");
        if (subscriptionId == null || generation <= 0 || now == null || snapshot == null) {
            throw new IllegalArgumentException("invalid delivery request");
        }
        ExternalDtoSnapshot.requireAllowList(snapshot, ExternalDtoSnapshot.OUTBOUND_FIELDS);
        ApiDelivery existing = mapper.selectByEventGeneration(eventId, subscriptionId, generation);
        if (existing != null) {
            if (!snapshot.payloadHash().equalsIgnoreCase(existing.getPayloadHash())) {
                throw new IllegalArgumentException("delivery payload conflicts with existing generation");
            }
            return existing;
        }
        ApiDelivery row = ApiDelivery.builder()
                .eventId(eventId)
                .subscriptionId(subscriptionId)
                .deliveryGeneration(generation)
                .clientId(clientId)
                .scopeCode(scopeCode)
                .tenantId(tenantId)
                .eventType(eventType)
                .schemaVersion(schemaVersion)
                .correlationId(correlationId)
                .providerIdempotencyKey(IntegrationHubDigest.sha256Hex(
                        eventId + "|" + subscriptionId + "|" + generation))
                .externalDtoSnapshot(snapshot.json())
                .payloadHash(snapshot.payloadHash().toLowerCase())
                .status(IntegrationHubStates.DELIVERY_PENDING)
                .attemptCount(0)
                .maxAttempts(MAX_ATTEMPTS)
                .nextAttemptAt(now)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            mapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            ApiDelivery concurrent = mapper.selectByEventGeneration(eventId, subscriptionId, generation);
            if (concurrent == null || !snapshot.payloadHash().equalsIgnoreCase(concurrent.getPayloadHash())) {
                throw new IllegalArgumentException("delivery generation conflicts");
            }
            return concurrent;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiDelivery claim(Long id, String leaseToken, LocalDateTime now, LocalDateTime leaseExpiresAt) {
        if (id == null || leaseToken == null || leaseToken.isBlank() || now == null
                || leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("invalid delivery lease");
        }
        ApiDelivery row = mapper.selectForUpdate(id);
        if (row == null) {
            return null;
        }
        if (row.getNextAttemptAt() != null && row.getNextAttemptAt().isAfter(now)) {
            return null;
        }
        if (!IntegrationHubStates.DELIVERY_PENDING.equals(row.getStatus())
                && !IntegrationHubStates.DELIVERY_RETRYABLE.equals(row.getStatus())) {
            return null;
        }
        if (mapper.claim(id, row.getVersion(), leaseToken, leaseExpiresAt, now) != 1) {
            return null;
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markSucceeded(Long id, Integer version, Integer generation, String leaseToken, String providerIdempotencyKey,
                                 String payloadHash,
                                 String providerRequestId, LocalDateTime now) {
        validateResult(id, version, generation, leaseToken, providerIdempotencyKey, payloadHash, now);
        return mapper.transitionSucceeded(id, version, generation, leaseToken, providerIdempotencyKey, payloadHash, providerRequestId,
                now, now.plusDays(30)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markRetryable(Long id, Integer version, Integer generation, String leaseToken, String providerIdempotencyKey,
                                 String payloadHash,
                                 String errorCode, LocalDateTime now, LocalDateTime nextAttemptAt) {
        validateResult(id, version, generation, leaseToken, providerIdempotencyKey, payloadHash, now);
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 64 || nextAttemptAt == null) {
            throw new IllegalArgumentException("invalid retry result");
        }
        ApiDelivery row = mapper.selectById(id);
        if (row == null || row.getAttemptCount() == null || row.getAttemptCount() >= MAX_ATTEMPTS) {
            return markTerminal(id, version, generation, leaseToken, providerIdempotencyKey, payloadHash, IntegrationHubStates.DELIVERY_DLQ,
                    "MAX_ATTEMPTS", now);
        }
        return mapper.transitionRetryable(id, version, generation, leaseToken, providerIdempotencyKey, payloadHash, errorCode,
                nextAttemptAt, now) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markTerminal(Long id, Integer version, Integer generation, String leaseToken, String providerIdempotencyKey,
                                String payloadHash,
                                String status, String errorCode, LocalDateTime now) {
        validateResult(id, version, generation, leaseToken, providerIdempotencyKey, payloadHash, now);
        if (!IntegrationHubStates.DELIVERY_FAILED.equals(status) && !IntegrationHubStates.DELIVERY_DLQ.equals(status)
                || errorCode == null || errorCode.isBlank() || errorCode.length() > 64) {
            throw new IllegalArgumentException("invalid terminal result");
        }
        return mapper.transitionTerminal(id, version, generation, leaseToken, providerIdempotencyKey, payloadHash, status, errorCode,
                now, now.plusDays(90)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        return mapper.recoverExpiredLeases(now);
    }

    private void validateResult(Long id, Integer version, Integer generation, String leaseToken, String providerIdempotencyKey,
                                String payloadHash, LocalDateTime now) {
        if (id == null || version == null || generation == null || generation <= 0
                || leaseToken == null || leaseToken.isBlank()
                || providerIdempotencyKey == null || !providerIdempotencyKey.matches("[0-9a-fA-F]{64}")
                || payloadHash == null || !payloadHash.matches("[0-9a-fA-F]{64}") || now == null) {
            throw new IllegalArgumentException("invalid delivery result");
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
