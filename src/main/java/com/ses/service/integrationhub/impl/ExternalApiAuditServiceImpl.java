package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ExternalApiAudit;
import com.ses.mapper.ExternalApiAuditMapper;
import com.ses.service.integrationhub.ExternalApiAuditRecord;
import com.ses.service.integrationhub.ExternalApiAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** bounded metadataを専用tableへ一request一rowで保存する。 */
@Service
@RequiredArgsConstructor
public class ExternalApiAuditServiceImpl implements ExternalApiAuditService {
    private final ExternalApiAuditMapper mapper;

    @Override
    public void recordRequired(ExternalApiAuditRecord record) {
        if (record == null || record.routeTemplate() == null || record.routeTemplate().length() > 200
                || record.method() == null || record.method().length() > 16
                || record.resultCode() == null || record.resultCode().length() > 64
                || record.correlationId() == null || record.correlationId().length() > 128) {
            throw new IllegalArgumentException("external audit metadata is invalid");
        }
        ExternalApiAudit entry = new ExternalApiAudit();
        entry.setPreAuthPrincipal(safe(record.preAuthPrincipal(), 64));
        entry.setPostAuthPrincipal(safe(record.postAuthPrincipal(), 128));
        entry.setClientId(safeNullable(record.clientId(), 100));
        entry.setCredentialVersion(record.credentialVersion());
        entry.setKeyId(safeNullable(record.keyId(), 100));
        entry.setCorrelationId(safe(record.correlationId(), 128));
        entry.setMethod(safe(record.method(), 16));
        entry.setRouteTemplate(safe(record.routeTemplate(), 200));
        entry.setAuthenticationDecision(safe(record.authenticationDecision(), 64));
        entry.setScopeDecision(safe(record.scopeDecision(), 64));
        entry.setDataScopeDecision(safe(record.dataScopeDecision(), 64));
        entry.setCommandDecision(safe(record.commandDecision(), 64));
        entry.setRateDecision(safe(record.rateDecision(), 64));
        entry.setStatus(record.status());
        entry.setResultCode(safe(record.resultCode(), 64));
        entry.setSuccessFlag(record.successFlag());
        entry.setCreatedAt(LocalDateTime.now());
        if (mapper.insert(entry) != 1) {
            throw new IllegalStateException("external API audit persistence failed");
        }
    }

    private String safe(String value, int max) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String safeNullable(String value, int max) {
        return value == null ? null : safe(value, max);
    }
}
