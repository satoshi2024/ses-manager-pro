package com.ses.service.servicedesk;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** 問い合わせの業務時刻・テナント・実行主体を一体で渡すコンテキスト。 */
public record ServiceDeskExecutionContext(
        String tenantId,
        ZoneId zoneId,
        Instant occurredAt,
        Long organizationId,
        Long legalEntityId,
        Long actorId,
        String actorType,
        String actorName,
        String source) {

    public ServiceDeskExecutionContext {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        Objects.requireNonNull(zoneId, "zoneId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        if (actorType == null || actorType.isBlank()) throw new IllegalArgumentException("actorType is required");
        if (actorName == null || actorName.isBlank()) throw new IllegalArgumentException("actorName is required");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
    }
}
