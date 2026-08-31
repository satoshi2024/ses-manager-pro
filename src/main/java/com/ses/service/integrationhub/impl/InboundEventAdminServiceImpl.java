package com.ses.service.integrationhub.impl;

import com.ses.common.util.PageUtils;
import com.ses.config.LoginUser;
import com.ses.dto.integrationhub.InboundEventAdminDto;
import com.ses.dto.integrationhub.InboundEventAdminPage;
import com.ses.dto.integrationhub.InboundEventAdminRow;
import com.ses.dto.integrationhub.InboundEventReplayResponse;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.entity.integrationhub.InboundEventReplayRequest;
import com.ses.mapper.InboundEventMapper;
import com.ses.mapper.InboundEventReplayRequestMapper;
import com.ses.service.integrationhub.InboundEventAdminService;
import com.ses.service.integrationhub.InboundEventAdminReferenceCodec;
import com.ses.service.integrationhub.InboundEventBindingValidator;
import com.ses.service.integrationhub.InboundEventProcessor;
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
    private static final String REPLAY_OPERATION = "integration.webhook.replay";
    private static final int MAX_PAGE_NUMBER = 1_000_000;

    private final InboundEventMapper inboundEventMapper;
    private final InboundEventReplayRequestMapper replayMapper;
    private final AuthorizationService authorizationService;
    private final InboundEventProcessor inboundEventProcessor;
    private final InboundEventBindingValidator bindingValidator;
    private final InboundEventAdminReferenceCodec referenceCodec;

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
    public InboundEventReplayResponse replay(String inboundEventReference, String reasonCode,
                                              Authentication authentication, LocalDateTime now) {
        requireAdmin(authentication, now);
        requireReason(reasonCode);
        if (inboundEventReference == null || inboundEventReference.isBlank() || now == null) {
            throw new IllegalArgumentException("invalid inbound replay request");
        }
        InboundEvent event = inboundEventMapper.selectByAdminReferenceForUpdate(inboundEventReference);
        if (event == null || !IntegrationHubStates.INBOUND_DLQ.equals(event.getStatus())) {
            throw new IllegalStateException("only inbound DLQ can be replayed");
        }
        if (!referenceCodec.matchesEvent(inboundEventReference, event.getClientId(), event.getProviderName(),
                event.getProviderEventId())) {
            throw new SecurityException("inbound admin reference is invalid");
        }
        bindingValidator.validateCurrent(event, now);
        Integer previous = replayMapper.selectMaxGeneration(event.getId());
        int generation = previous == null ? 1 : previous + 1;
        if (generation <= 0) {
            throw new IllegalStateException("inbound replay generation overflow");
        }
        String operatorRef = operatorRef(authentication);
        InboundEventReplayRequest request = InboundEventReplayRequest.builder()
                .inboundEventId(event.getId())
                .replayReference(referenceCodec.replayReference(inboundEventReference, generation))
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
        return new InboundEventReplayResponse(request.getReplayReference(), generation, request.getStatus(), "REPLAY_REQUESTED");
    }

    /** request後に別transactionで1回だけlocal processorを実行する。 */
    @Transactional(rollbackFor = Exception.class)
    public InboundEventReplayResponse processReplay(String replayReference, Authentication authentication,
                                                    LocalDateTime now) {
        requireAdmin(authentication, now);
        if (replayReference == null || replayReference.isBlank() || now == null) {
            throw new IllegalArgumentException("invalid inbound replay process request");
        }
        InboundEventReplayRequest request = replayMapper.selectByReplayReferenceForUpdate(replayReference);
        if (request == null) {
            throw new IllegalStateException("inbound replay request not found");
        }
        if (!"REQUESTED".equals(request.getStatus())) {
            return replayResponse(request);
        }
        if (replayMapper.claim(request.getId(), request.getVersion(), now) != 1) {
            return replayResponse(replayMapper.selectByReplayReferenceForUpdate(replayReference));
        }
        InboundEventReplayRequest claimed = replayMapper.selectForUpdate(request.getId());
        InboundEvent source = claimed.getInboundEventId() == null
                ? null : inboundEventMapper.selectForUpdate(claimed.getInboundEventId());
        if (source == null || !IntegrationHubStates.INBOUND_DLQ.equals(source.getStatus())) {
            completeReplay(claimed, "REJECTED", "SOURCE_EXPIRED_OR_NOT_DLQ", now);
            return replayResponse(replayMapper.selectByReplayReferenceForUpdate(replayReference));
        }
        try {
            bindingValidator.validateCurrent(source, now);
        } catch (RuntimeException e) {
            // 現行subscription/scopeの不一致は再試行しても安全にならないため、
            // processorへ渡さず独立したREJECTED監査結果に固定する。
            completeReplay(claimed, "REJECTED", "CURRENT_SCOPE_INVALID", now);
            return replayResponse(replayMapper.selectByReplayReferenceForUpdate(replayReference));
        }
        try {
            inboundEventProcessor.process(source);
        } catch (RuntimeException e) {
            completeReplay(claimed, "DLQ", "REPLAY_PROCESSING_FAILED", now);
            return replayResponse(replayMapper.selectByReplayReferenceForUpdate(replayReference));
        }
        completeReplay(claimed, "PROCESSED", "REPLAY_ACCEPTED", now);
        return replayResponse(replayMapper.selectByReplayReferenceForUpdate(replayReference));
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
        return new InboundEventReplayResponse(request.getReplayReference(), request.getReplayGeneration(),
                request.getStatus(), request.getResultCode());
    }
    private void requireAdmin(Authentication authentication, LocalDateTime now) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)
                || loginUser.getSysUser() == null || loginUser.getSysUser().getId() == null
                || loginUser.getSysUser().getStatus() == null || loginUser.getSysUser().getStatus() != 1
                || (loginUser.getSysUser().getLockedUntil() != null
                && (now == null || loginUser.getSysUser().getLockedUntil().isAfter(now)))
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_管理者".equals(a.getAuthority()))) {
            throw new AccessDeniedException("admin permission required");
        }
        authorizationService.assertAllowed(authentication, REPLAY_OPERATION);
    }

    private String operatorRef(Authentication authentication) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        Long userId = loginUser.getSysUser().getId();
        if (userId == null || userId <= 0) throw new AccessDeniedException("authenticated internal operator is missing");
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
        return new InboundEventAdminDto(row.getReference(), row.getClientId(), row.getProviderName(),
                row.getProviderEventId(), row.getSignatureValid(), row.getStatus(), row.getResultCode(),
                row.getReceivedAt(), row.getProcessedAt(), row.getRetentionExpiresAt());
    }
}
