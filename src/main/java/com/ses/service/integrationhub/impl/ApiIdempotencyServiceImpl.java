package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.ApiIdempotencyRecord;
import com.ses.mapper.ApiIdempotencyRecordMapper;
import com.ses.service.integrationhub.ApiIdempotencyService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.IdempotencyPayloadConflictException;
import com.ses.service.integrationhub.IntegrationHubStates;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** NF-05 idempotency implementation。raw requestをDBへ渡さない。 */
@Service
@RequiredArgsConstructor
public class ApiIdempotencyServiceImpl implements ApiIdempotencyService {
    private final ApiIdempotencyRecordMapper mapper;
    private static final int MAX_KEY_LENGTH = 200;
    private static final int MAX_ROUTE_LENGTH = 255;
    private static final int MAX_DIGEST_LENGTH = 64;

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = IdempotencyPayloadConflictException.class)
    public Reservation reserve(String clientId, String routeTemplate, String idempotencyKey,
                                String requestDigest, LocalDateTime now) {
        validate(clientId, routeTemplate, idempotencyKey, requestDigest, now);
        ApiIdempotencyRecord existing = mapper.selectByNaturalKeyForUpdate(clientId, routeTemplate, idempotencyKey);
        if (existing != null) {
            return resolveExisting(existing, requestDigest, now);
        }

        ApiIdempotencyRecord created = ApiIdempotencyRecord.builder()
                .clientId(clientId)
                .routeTemplate(routeTemplate)
                .idempotencyKey(idempotencyKey)
                .requestDigest(requestDigest)
                .status(IntegrationHubStates.IDEMPOTENCY_IN_PROGRESS)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            mapper.insert(created);
            return new Reservation(created, false);
        } catch (DuplicateKeyException e) {
            // unique conflictは一度だけ再読込し、同一digestだけ既存recordへ収束する。
            ApiIdempotencyRecord concurrent = mapper.selectByNaturalKeyForUpdate(clientId, routeTemplate, idempotencyKey);
            if (concurrent == null) {
                throw e;
            }
            return resolveExisting(concurrent, requestDigest, now);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeSucceeded(Long id, Integer version, String requestDigest, Integer responseStatus,
                                     ExternalDtoSnapshot safeResponseSnapshot, LocalDateTime terminalAt) {
        validateTransition(id, version, requestDigest, terminalAt);
        if (safeResponseSnapshot != null) {
            ExternalDtoSnapshot.requireAllowList(safeResponseSnapshot, ExternalDtoSnapshot.SAFE_RESPONSE_FIELDS);
        }
        return mapper.transitionSucceeded(id, version, requestDigest, responseStatus,
                safeResponseSnapshot == null ? null : safeResponseSnapshot.json(),
                terminalAt, terminalAt.plusDays(30)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeFailed(Long id, Integer version, String requestDigest, Integer responseStatus,
                                  ExternalDtoSnapshot safeResponseSnapshot, LocalDateTime terminalAt) {
        validateTransition(id, version, requestDigest, terminalAt);
        if (safeResponseSnapshot != null) {
            ExternalDtoSnapshot.requireAllowList(safeResponseSnapshot, ExternalDtoSnapshot.SAFE_RESPONSE_FIELDS);
        }
        return mapper.transitionFailed(id, version, requestDigest, responseStatus,
                safeResponseSnapshot == null ? null : safeResponseSnapshot.json(),
                terminalAt, terminalAt.plusDays(90)) == 1;
    }

    private Reservation resolveExisting(ApiIdempotencyRecord existing, String requestDigest, LocalDateTime now) {
        if (!requestDigest.equals(existing.getRequestDigest())) {
            if (IntegrationHubStates.IDEMPOTENCY_IN_PROGRESS.equals(existing.getStatus())) {
                mapper.transitionConflict(existing.getId(), existing.getVersion(), existing.getRequestDigest(),
                        now, now.plusDays(90));
            }
            throw new IdempotencyPayloadConflictException();
        }
        return new Reservation(existing, true);
    }

    private void validate(String clientId, String routeTemplate, String idempotencyKey,
                          String requestDigest, LocalDateTime now) {
        requireText(clientId, 100, "clientId");
        requireText(routeTemplate, MAX_ROUTE_LENGTH, "routeTemplate");
        requireText(idempotencyKey, MAX_KEY_LENGTH, "idempotencyKey");
        requireText(requestDigest, MAX_DIGEST_LENGTH, "requestDigest");
        if (!requestDigest.matches("[0-9a-fA-F]{64}") || now == null) {
            throw new IllegalArgumentException("invalid idempotency request");
        }
    }

    private void validateTransition(Long id, Integer version, String digest, LocalDateTime terminalAt) {
        if (id == null || version == null || digest == null || !digest.matches("[0-9a-fA-F]{64}") || terminalAt == null) {
            throw new IllegalArgumentException("invalid idempotency transition");
        }
    }

    private void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }
}
