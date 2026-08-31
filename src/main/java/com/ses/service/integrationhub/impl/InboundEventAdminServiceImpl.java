package com.ses.service.integrationhub.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.config.LoginUser;
import com.ses.config.integrationhub.ExternalApiDataScope;
import com.ses.dto.integrationhub.InboundEventAdminDto;
import com.ses.dto.integrationhub.InboundEventAdminPage;
import com.ses.dto.integrationhub.InboundEventAdminRow;
import com.ses.dto.integrationhub.InboundEventReplayResponse;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.ApiClientScope;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.entity.integrationhub.InboundEventReplayRequest;
import com.ses.entity.integrationhub.WebhookSubscription;
import com.ses.mapper.ApiClientMapper;
import com.ses.mapper.ApiClientScopeMapper;
import com.ses.mapper.InboundEventMapper;
import com.ses.mapper.InboundEventReplayRequestMapper;
import com.ses.mapper.WebhookSubscriptionMapper;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.InboundEventAdminService;
import com.ses.service.integrationhub.InboundEventProcessor;
import com.ses.service.integrationhub.InboundEventService;
import com.ses.service.integrationhub.IntegrationHubStates;
import com.ses.service.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * inbound event一覧とDLQ replayをservice boundaryへ集約する。
 * replayは元eventを再利用するが逆遷移せず、外部HTTPも呼ばない。
 */
@Service
@RequiredArgsConstructor
public class InboundEventAdminServiceImpl implements InboundEventAdminService {
    private static final Set<String> STATUSES = Set.of(
            IntegrationHubStates.INBOUND_RECEIVED, IntegrationHubStates.INBOUND_PROCESSING,
            IntegrationHubStates.INBOUND_PROCESSED, IntegrationHubStates.INBOUND_DUPLICATE,
            IntegrationHubStates.INBOUND_CONFLICT, IntegrationHubStates.INBOUND_DLQ);
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,100}");
    private static final Pattern REASON_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final String RECEIVE_SCOPE = "integration.webhook.receive";
    private static final String RECEIVE_OPERATION = "integration.webhook.receive";
    private static final String REPLAY_OPERATION = "integration.webhook.replay";
    private static final int MAX_PAGE_NUMBER = 1_000_000;

    private final InboundEventMapper inboundEventMapper;
    private final InboundEventReplayRequestMapper replayMapper;
    private final ApiClientMapper clientMapper;
    private final ApiClientScopeMapper clientScopeMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final AuthorizationService authorizationService;
    private final InboundEventProcessor inboundEventProcessor;
    private final ObjectMapper objectMapper;

    @Override
    public InboundEventAdminPage page(long current, long size, String status, String providerName) {
        String safeStatus = normalizeStatus(status);
        String safeProvider = normalizeProvider(providerName);
        var safePage = PageUtils.safePage(Math.min(Math.max(current, 1L), MAX_PAGE_NUMBER), size, 25, 100);
        long offset = (safePage.getCurrent() - 1L) * safePage.getSize();
        List<InboundEventAdminDto> records = inboundEventMapper.selectAdminPage(
                        safeStatus, safeProvider, safePage.getSize(), offset).stream()
                .map(this::toDto).toList();
        return new InboundEventAdminPage(records,
                inboundEventMapper.countAdminPage(safeStatus, safeProvider),
                safePage.getCurrent(), safePage.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundEventReplayResponse replay(Long inboundEventId, String reasonCode,
                                              Authentication authentication, LocalDateTime now) {
        requireAdmin(authentication);
        requireReason(reasonCode);
        if (inboundEventId == null || now == null) {
            throw new IllegalArgumentException("invalid inbound replay request");
        }
        InboundEvent event = inboundEventMapper.selectForUpdate(inboundEventId);
        if (event == null || !IntegrationHubStates.INBOUND_DLQ.equals(event.getStatus())) {
            throw new IllegalStateException("only inbound DLQ can be replayed");
        }
        validateCurrentBinding(event, now);
        Integer previous = replayMapper.selectMaxGeneration(inboundEventId);
        int generation = previous == null ? 1 : previous + 1;
        if (generation <= 0) {
            throw new IllegalStateException("inbound replay generation overflow");
        }
        String operatorRef = operatorRef(authentication);
        InboundEventReplayRequest request = InboundEventReplayRequest.builder()
                .inboundEventId(event.getId())
                .clientId(event.getClientId())
                .providerName(event.getProviderName())
                .providerEventId(event.getProviderEventId())
                .rawBodyHash(event.getRawBodyHash())
                .replayGeneration(generation)
                .operatorRef(operatorRef)
                .reasonCode(reasonCode)
                .status("REQUESTED")
                .retentionClass(IntegrationHubStates.RETENTION_AUDIT_1Y)
                .retentionExpiresAt(now.plusYears(1))
                .createdAt(now)
                .updatedAt(now)
                .version(0)
                .build();
        try {
            replayMapper.insert(request);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("inbound replay generation conflict", e);
        }

        // request ledgerのcommitとreplay処理を同じtransactionへ混ぜないため、
        // このserviceはREQUESTEDを返し、controllerが別呼出しのprocess boundaryを実行する。
        return new InboundEventReplayResponse(request.getId(), generation, request.getStatus(), "REPLAY_REQUESTED");
    }

    /** request後に別transactionで1回だけlocal processorを実行する。 */
    @Transactional(rollbackFor = Exception.class)
    public InboundEventReplayResponse processReplay(Long requestId, Authentication authentication,
                                                    LocalDateTime now) {
        requireAdmin(authentication);
        if (requestId == null || now == null) {
            throw new IllegalArgumentException("invalid inbound replay process request");
        }
        InboundEventReplayRequest request = replayMapper.selectForUpdate(requestId);
        if (request == null) {
            throw new IllegalStateException("inbound replay request not found");
        }
        if (!"REQUESTED".equals(request.getStatus())) {
            return replayResponse(request);
        }
        if (replayMapper.claim(request.getId(), request.getVersion(), now) != 1) {
            return replayResponse(replayMapper.selectForUpdate(requestId));
        }
        InboundEventReplayRequest claimed = replayMapper.selectForUpdate(requestId);
        InboundEvent source = claimed.getInboundEventId() == null
                ? null : inboundEventMapper.selectForUpdate(claimed.getInboundEventId());
        if (source == null || !IntegrationHubStates.INBOUND_DLQ.equals(source.getStatus())) {
            completeReplay(claimed, "REJECTED", "SOURCE_EXPIRED_OR_NOT_DLQ", now);
            return replayResponse(replayMapper.selectForUpdate(requestId));
        }
        try {
            validateCurrentBinding(source, now);
        } catch (RuntimeException e) {
            // 現行subscription/scopeの不一致は再試行しても安全にならないため、
            // processorへ渡さず独立したREJECTED監査結果に固定する。
            completeReplay(claimed, "REJECTED", "CURRENT_SCOPE_INVALID", now);
            return replayResponse(replayMapper.selectForUpdate(requestId));
        }
        try {
            inboundEventProcessor.process(source);
        } catch (RuntimeException e) {
            completeReplay(claimed, "DLQ", "REPLAY_PROCESSING_FAILED", now);
            return replayResponse(replayMapper.selectForUpdate(requestId));
        }
        completeReplay(claimed, "PROCESSED", "REPLAY_ACCEPTED", now);
        return replayResponse(replayMapper.selectForUpdate(requestId));
    }

    private void completeReplay(InboundEventReplayRequest request, String status,
                                String resultCode, LocalDateTime now) {
        if (replayMapper.complete(request.getId(), request.getVersion(), status, resultCode, now) != 1) {
            throw new IllegalStateException("inbound replay terminal CAS failed");
        }
    }

    private InboundEventReplayResponse replayResponse(InboundEventReplayRequest request) {
        if (request == null) {
            throw new IllegalStateException("inbound replay state disappeared");
        }
        return new InboundEventReplayResponse(request.getId(), request.getReplayGeneration(),
                request.getStatus(), request.getResultCode());
    }

    private void validateCurrentBinding(InboundEvent event, LocalDateTime now) {
        if (event.getClientId() == null || event.getProviderName() == null || event.getRawBodyHash() == null
                || event.getParsedFieldsSnapshot() == null) {
            throw new SecurityException("inbound event binding is incomplete");
        }
        ApiClient client = clientMapper.selectByClientIdForUpdate(event.getClientId());
        if (client == null || client.getId() == null || !"ACTIVE".equals(client.getStatus())
                || client.getRevokedAt() != null || (client.getExpiresAt() != null && !client.getExpiresAt().isAfter(now))
                || client.getTenantId() == null || client.getLegalEntityId() == null) {
            throw new SecurityException("inbound client is not active");
        }
        ExternalDtoSnapshot snapshot = ExternalDtoSnapshot.ofAllowList(
                event.getParsedFieldsSnapshot(), ExternalDtoSnapshot.INBOUND_FIELDS);
        String eventType = eventType(snapshot);
        WebhookSubscription subscription = subscriptionMapper.selectActiveInboundByClientAndEvent(
                event.getClientId(), eventType);
        if (subscription == null || !"INBOUND".equals(subscription.getDirection())) {
            throw new SecurityException("inbound subscription is not active");
        }
        ApiClientScope permission = clientScopeMapper.selectActive(
                client.getId(), RECEIVE_SCOPE, RECEIVE_OPERATION);
        if (permission == null) {
            throw new SecurityException("inbound permission is not active");
        }
        ExternalApiDataScope clientScope = ExternalApiDataScope.parse(client.getDataScopeJson(), objectMapper);
        ExternalApiDataScope permissionScope = ExternalApiDataScope.parse(permission.getDataScopeJson(), objectMapper);
        ExternalApiDataScope subscriptionScope = ExternalApiDataScope.parse(subscription.getDataScopeJson(), objectMapper);
        requireExactBinding(clientScope, client.getTenantId(), client.getLegalEntityId());
        requireExactBinding(permissionScope, client.getTenantId(), client.getLegalEntityId());
        requireExactBinding(subscriptionScope, client.getTenantId(), client.getLegalEntityId());
        ExternalApiDataScope effective = clientScope.intersect(permissionScope).intersect(subscriptionScope);
        requireExactBinding(effective, client.getTenantId(), client.getLegalEntityId());
    }

    private String eventType(ExternalDtoSnapshot snapshot) {
        try {
            JsonNode node = objectMapper.readTree(snapshot.json()).get("eventType");
            if (node == null || !node.isTextual() || !node.textValue().matches("[A-Za-z][A-Za-z0-9._:-]{0,99}")) {
                throw new IllegalArgumentException("inbound event type is invalid");
            }
            return node.textValue();
        } catch (Exception e) {
            throw new IllegalArgumentException("inbound event type is invalid", e);
        }
    }

    private void requireExactBinding(ExternalApiDataScope scope, String tenantId, Long legalEntityId) {
        if (scope == null || !Set.of(tenantId).equals(scope.values().get("tenantIds"))
                || !Set.of(Long.toString(legalEntityId)).equals(scope.values().get("legalEntityIds"))) {
            throw new SecurityException("inbound scope binding is invalid");
        }
    }

    private void requireAdmin(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_管理者".equals(a.getAuthority()))) {
            throw new AccessDeniedException("admin permission required");
        }
        authorizationService.assertAllowed(authentication, REPLAY_OPERATION);
    }

    private String operatorRef(Authentication authentication) {
        Long userId = null;
        if (authentication.getPrincipal() instanceof LoginUser loginUser
                && loginUser.getSysUser() != null) {
            userId = loginUser.getSysUser().getId();
        }
        if (userId == null) {
            userId = SecurityUtils.currentUserId();
        }
        if (userId == null || userId <= 0) {
            throw new AccessDeniedException("authenticated internal operator is missing");
        }
        return "sys-user:" + userId;
    }

    private String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        if (!STATUSES.contains(value)) throw new IllegalArgumentException("invalid inbound status filter");
        return value;
    }

    private String normalizeProvider(String value) {
        if (!StringUtils.hasText(value)) return null;
        if (!PROVIDER_PATTERN.matcher(value).matches()) throw new IllegalArgumentException("invalid provider filter");
        return value;
    }

    private void requireReason(String value) {
        if (value == null || !REASON_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid replay reason");
        }
    }

    private InboundEventAdminDto toDto(InboundEventAdminRow row) {
        return new InboundEventAdminDto(row.getId(), row.getClientId(), row.getProviderName(),
                row.getProviderEventId(), row.getSignatureValid(), row.getStatus(), row.getResultCode(),
                row.getReceivedAt(), row.getProcessedAt(), row.getRetentionExpiresAt());
    }
}
