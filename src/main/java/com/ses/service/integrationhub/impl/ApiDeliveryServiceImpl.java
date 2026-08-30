package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
public class ApiDeliveryServiceImpl extends ServiceImpl<ApiDeliveryMapper, ApiDelivery>
        implements ApiDeliveryService {
    private static final int MAX_ATTEMPTS = 8;

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
        ApiDelivery existing = baseMapper.selectByEventGeneration(eventId, subscriptionId, generation);
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
            baseMapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            ApiDelivery concurrent = baseMapper.selectByEventGeneration(eventId, subscriptionId, generation);
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
        ApiDelivery row = baseMapper.selectForUpdate(id);
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
        if (baseMapper.claim(id, row.getVersion(), leaseToken, leaseExpiresAt, now) != 1) {
            return null;
        }
        return baseMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markSucceeded(Long id, Integer version, String leaseToken, String payloadHash,
                                 String providerRequestId, LocalDateTime now) {
        validateResult(id, version, leaseToken, payloadHash, now);
        return baseMapper.transitionSucceeded(id, version, leaseToken, payloadHash, providerRequestId,
                now, now.plusDays(30)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markRetryable(Long id, Integer version, String leaseToken, String payloadHash,
                                 String errorCode, LocalDateTime now, LocalDateTime nextAttemptAt) {
        validateResult(id, version, leaseToken, payloadHash, now);
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 64 || nextAttemptAt == null) {
            throw new IllegalArgumentException("invalid retry result");
        }
        ApiDelivery row = baseMapper.selectById(id);
        if (row == null || row.getAttemptCount() == null || row.getAttemptCount() >= MAX_ATTEMPTS) {
            return markTerminal(id, version, leaseToken, payloadHash, IntegrationHubStates.DELIVERY_DLQ,
                    "MAX_ATTEMPTS", now);
        }
        return baseMapper.transitionRetryable(id, version, leaseToken, payloadHash, errorCode,
                nextAttemptAt, now) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markTerminal(Long id, Integer version, String leaseToken, String payloadHash,
                                String status, String errorCode, LocalDateTime now) {
        validateResult(id, version, leaseToken, payloadHash, now);
        if (!IntegrationHubStates.DELIVERY_FAILED.equals(status) && !IntegrationHubStates.DELIVERY_DLQ.equals(status)
                || errorCode == null || errorCode.isBlank() || errorCode.length() > 64) {
            throw new IllegalArgumentException("invalid terminal result");
        }
        return baseMapper.transitionTerminal(id, version, leaseToken, payloadHash, status, errorCode,
                now, now.plusDays(90)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        return baseMapper.recoverExpiredLeases(now);
    }

    private void validateResult(Long id, Integer version, String leaseToken, String payloadHash, LocalDateTime now) {
        if (id == null || version == null || leaseToken == null || leaseToken.isBlank()
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
