package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiIdempotencyRecord;

import java.time.LocalDateTime;

/** NF-05 idempotency digest/safe response persistence service。 */
public interface ApiIdempotencyService {
    Reservation reserve(String clientId, String routeTemplate, String idempotencyKey,
                        String requestDigest, LocalDateTime now);

    boolean completeSucceeded(Long id, Integer version, String requestDigest, Integer responseStatus,
                              ExternalDtoSnapshot safeResponseSnapshot, LocalDateTime terminalAt);

    boolean completeFailed(Long id, Integer version, String requestDigest, Integer responseStatus,
                           ExternalDtoSnapshot safeResponseSnapshot, LocalDateTime terminalAt);

    record Reservation(ApiIdempotencyRecord record, boolean reused) {
    }
}
