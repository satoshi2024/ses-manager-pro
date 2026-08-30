package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.mapper.InboundEventMapper;
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
public class InboundEventServiceImpl extends ServiceImpl<InboundEventMapper, InboundEvent>
        implements InboundEventService {
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Receipt recordReceived(String clientId, String providerName, String providerEventId, String rawBodyHash,
                                  LocalDateTime signedTimestamp, String parsedFieldsSnapshot,
                                  boolean signatureValid, LocalDateTime receivedAt) {
        requireText(clientId, 100, "clientId");
        requireText(providerName, 100, "providerName");
        requireText(providerEventId, 160, "providerEventId");
        requireHash(rawBodyHash);
        if (signedTimestamp == null || receivedAt == null || parsedFieldsSnapshot != null && parsedFieldsSnapshot.length() > 65535) {
            throw new IllegalArgumentException("invalid inbound event");
        }
        InboundEvent existing = baseMapper.selectByProviderEvent(clientId, providerName, providerEventId);
        if (existing != null) {
            if (rawBodyHash.equalsIgnoreCase(existing.getRawBodyHash())) {
                return new Receipt(existing, true, false);
            }
            if (existing.getVersion() != null) {
                baseMapper.transitionConflict(existing.getId(), existing.getVersion(), rawBodyHash,
                        receivedAt, receivedAt.plusDays(90));
            }
            return new Receipt(existing, true, true);
        }
        InboundEvent row = InboundEvent.builder()
                .clientId(clientId)
                .providerName(providerName)
                .providerEventId(providerEventId)
                .rawBodyHash(rawBodyHash.toLowerCase())
                .signedTimestamp(signedTimestamp)
                .parsedFieldsSnapshot(parsedFieldsSnapshot)
                .signatureValid(signatureValid)
                .status(IntegrationHubStates.INBOUND_RECEIVED)
                .receivedAt(receivedAt)
                .version(0)
                .createdAt(receivedAt)
                .updatedAt(receivedAt)
                .build();
        try {
            baseMapper.insert(row);
            return new Receipt(row, false, false);
        } catch (DuplicateKeyException e) {
            InboundEvent concurrent = baseMapper.selectByProviderEvent(clientId, providerName, providerEventId);
            if (concurrent == null) {
                throw e;
            }
            boolean conflict = !rawBodyHash.equalsIgnoreCase(concurrent.getRawBodyHash());
            return new Receipt(concurrent, true, conflict);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundEvent claim(Long id, LocalDateTime now) {
        if (id == null || now == null) {
            throw new IllegalArgumentException("invalid inbound claim");
        }
        InboundEvent row = baseMapper.selectForUpdate(id);
        if (row == null || !IntegrationHubStates.INBOUND_RECEIVED.equals(row.getStatus())) {
            return null;
        }
        if (baseMapper.claim(id, row.getVersion(), now) != 1) {
            return null;
        }
        return baseMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Long id, Integer version, String status, String resultCode, LocalDateTime terminalAt) {
        if (id == null || version == null || resultCode == null || resultCode.isBlank()
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
        return baseMapper.transitionTerminal(id, version, status, resultCode, retention,
                terminalAt, terminalAt.plusDays(retention.equals(IntegrationHubStates.RETENTION_SUCCEEDED_30D) ? 30 : 90)) == 1;
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
}
