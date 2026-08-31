package com.ses.service.integrationhub.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.mapper.InboundEventMapper;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.InboundEventAdminReferenceCodec;
import com.ses.service.integrationhub.InboundEventBindingValidator;
import com.ses.service.integrationhub.InboundEventService;
import com.ses.service.integrationhub.IntegrationHubStates;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** NF-05 inbound replay ledger implementation。 */
@Service
@RequiredArgsConstructor
public class InboundEventServiceImpl implements InboundEventService {
    private final InboundEventMapper mapper;
    private final InboundEventBindingValidator bindingValidator;
    private final InboundEventAdminReferenceCodec referenceCodec;
    private final ObjectMapper objectMapper;
    private final IntegrationHubExternalApiProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Receipt recordReceived(String clientId, String providerName, String providerEventId, String rawBodyHash,
                                  LocalDateTime signedTimestamp, ExternalDtoSnapshot parsedFieldsSnapshot,
                                  boolean signatureValid, LocalDateTime receivedAt) {
        requireText(clientId, 100, "clientId");
        requireText(providerName, 100, "providerName");
        requireText(providerEventId, 160, "providerEventId");
        requireHash(rawBodyHash);
        if (signedTimestamp == null || receivedAt == null || parsedFieldsSnapshot == null) {
            throw new IllegalArgumentException("invalid inbound event");
        }
        recoverExpiredLeases(receivedAt);
        ExternalDtoSnapshot.requireAllowList(parsedFieldsSnapshot, ExternalDtoSnapshot.INBOUND_FIELDS);
        InboundEventBindingValidator.Binding binding = bindingValidator.validateForReceipt(
                clientId, providerName, eventType(parsedFieldsSnapshot), parsedFieldsSnapshot, receivedAt);
        InboundEvent existing = mapper.selectByProviderEvent(clientId, providerName, providerEventId);
        if (existing != null) {
            if (rawBodyHash.equalsIgnoreCase(existing.getRawBodyHash())) {
                return receiptForSameHash(existing, receivedAt);
            }
            if (IntegrationHubStates.INBOUND_RECEIVED.equals(existing.getStatus())
                    || IntegrationHubStates.INBOUND_PROCESSING.equals(existing.getStatus())) {
                mapper.transitionConflict(existing.getId(), existing.getVersion(), rawBodyHash,
                        receivedAt, receivedAt.plusDays(90));
                existing = mapper.selectForUpdate(existing.getId());
            }
            return new Receipt(existing, true, true);
        }
        InboundEvent row = InboundEvent.builder()
                .clientId(clientId)
                .providerName(providerName)
                .providerEventId(providerEventId)
                .adminReference(referenceCodec.eventReference(clientId, providerName, providerEventId))
                .primaryResourceType(binding.primaryResourceType())
                .primaryResourceId(binding.primaryResourceId())
                .rawBodyHash(rawBodyHash.toLowerCase())
                .signedTimestamp(signedTimestamp)
                .parsedFieldsSnapshot(parsedFieldsSnapshot.json())
                .signatureValid(signatureValid)
                .status(IntegrationHubStates.INBOUND_RECEIVED)
                .receivedAt(receivedAt)
                .version(0)
                .createdAt(receivedAt)
                .updatedAt(receivedAt)
                .build();
        try {
            mapper.insert(row);
            return new Receipt(row, false, false);
        } catch (DuplicateKeyException e) {
            InboundEvent concurrent = mapper.selectByProviderEventForUpdate(clientId, providerName, providerEventId);
            if (concurrent == null) {
                throw e;
            }
            if (rawBodyHash.equalsIgnoreCase(concurrent.getRawBodyHash())) {
                return receiptForSameHash(concurrent, receivedAt);
            }
            if (IntegrationHubStates.INBOUND_RECEIVED.equals(concurrent.getStatus())
                    || IntegrationHubStates.INBOUND_PROCESSING.equals(concurrent.getStatus())) {
                mapper.transitionConflict(concurrent.getId(), concurrent.getVersion(), rawBodyHash,
                        receivedAt, receivedAt.plusDays(90));
                concurrent = mapper.selectForUpdate(concurrent.getId());
            }
            return new Receipt(concurrent, true, true);
        }
    }

    /** 同一hash再送。terminalのみduplicate、有効lease中はinProgress、それ以外はclaim可能な非terminal再試行。 */
    private Receipt receiptForSameHash(InboundEvent existing, LocalDateTime receivedAt) {
        if (IntegrationHubStates.INBOUND_PROCESSING.equals(existing.getStatus())
                && leaseActive(existing, receivedAt)) {
            return new Receipt(existing, false, false, true);
        }
        if (isTerminalDuplicate(existing.getStatus())) {
            return new Receipt(existing, true, false);
        }
        return new Receipt(existing, false, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundEvent claim(Long id, String leaseToken, LocalDateTime now, LocalDateTime leaseExpiresAt) {
        if (id == null || leaseToken == null || leaseToken.isBlank() || now == null
                || leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("invalid inbound claim");
        }
        recoverExpiredLeases(now);
        InboundEvent row = mapper.selectForUpdate(id);
        if (row == null) {
            return null;
        }
        if (IntegrationHubStates.INBOUND_RECEIVED.equals(row.getStatus())) {
            if (mapper.claim(id, row.getVersion(), leaseToken, leaseExpiresAt, now) != 1) {
                return null;
            }
        } else if (IntegrationHubStates.INBOUND_PROCESSING.equals(row.getStatus())
                && row.getLeaseExpiresAt() != null && !row.getLeaseExpiresAt().isAfter(now)) {
            if (mapper.reclaimExpired(id, row.getVersion(), leaseToken, leaseExpiresAt, now) != 1) {
                return null;
            }
        } else {
            return null;
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Long id, Integer version, String leaseToken, String status, String resultCode,
                            LocalDateTime terminalAt) {
        if (id == null || version == null || leaseToken == null || leaseToken.isBlank()
                || resultCode == null || resultCode.isBlank()
                || resultCode.length() > 64 || terminalAt == null) {
            throw new IllegalArgumentException("invalid inbound result");
        }
        String retention = IntegrationHubStates.INBOUND_PROCESSED.equals(status)
                || IntegrationHubStates.INBOUND_DUPLICATE.equals(status)
                ? IntegrationHubStates.RETENTION_SUCCEEDED_30D : IntegrationHubStates.RETENTION_FAILED_90D;
        if (!IntegrationHubStates.INBOUND_PROCESSED.equals(status)
                && !IntegrationHubStates.INBOUND_DUPLICATE.equals(status)
                && !IntegrationHubStates.INBOUND_CONFLICT.equals(status)
                && !IntegrationHubStates.INBOUND_DLQ.equals(status)) {
            throw new IllegalArgumentException("invalid inbound terminal status");
        }
        return mapper.transitionTerminal(id, version, leaseToken, status, resultCode, retention,
                terminalAt, terminalAt.plusDays(retention.equals(IntegrationHubStates.RETENTION_SUCCEEDED_30D) ? 30 : 90)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        return mapper.recoverExpiredLeases(now);
    }

    private boolean leaseActive(InboundEvent event, LocalDateTime now) {
        return event.getLeaseExpiresAt() != null && event.getLeaseExpiresAt().isAfter(now);
    }

    private boolean isTerminalDuplicate(String status) {
        return IntegrationHubStates.INBOUND_PROCESSED.equals(status)
                || IntegrationHubStates.INBOUND_DUPLICATE.equals(status)
                || IntegrationHubStates.INBOUND_CONFLICT.equals(status)
                || IntegrationHubStates.INBOUND_DLQ.equals(status);
    }

    private void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    private void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invalid raw body hash");
        }
    }

    private String eventType(ExternalDtoSnapshot snapshot) {
        try {
            JsonNode value = objectMapper.readTree(snapshot.json()).get("eventType");
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("invalid inbound event type");
            }
            return value.textValue();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid inbound event type", e);
        }
    }
}
