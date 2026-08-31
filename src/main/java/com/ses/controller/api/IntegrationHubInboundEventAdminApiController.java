package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.integrationhub.InboundEventAdminPage;
import com.ses.dto.integrationhub.InboundEventReplayRequestDto;
import com.ses.dto.integrationhub.InboundEventReplayResponse;
import com.ses.service.integrationhub.InboundEventAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** NF-05 B2 inbound event/DLQ管理API。admin roleとaction permissionを二重に要求する。 */
@RestController
@RequestMapping("/api/integration-hub/inbound-events")
@PreAuthorize("hasRole('管理者')")
@RequiredArgsConstructor
public class IntegrationHubInboundEventAdminApiController {
    private final InboundEventAdminService service;
    private final Clock clock;

    @GetMapping
    public ApiResult<InboundEventAdminPage> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "25") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerName) {
        return ApiResult.success(service.page(current, size, status, providerName));
    }

    @PostMapping("/{reference}/replay")
    public ApiResult<InboundEventReplayResponse> replay(
            @PathVariable("reference") String reference,
            @Valid @RequestBody InboundEventReplayRequestDto request,
            Authentication authentication) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        InboundEventReplayResponse created = service.replay(reference, request.reasonCode(), authentication, now);
        InboundEventReplayResponse processed = service.processReplay(created.replayReference(), authentication, now);
        return ApiResult.success(processed);
    }
}
