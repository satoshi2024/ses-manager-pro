package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.service.integrationhub.IntegrationHubManagementService;
import com.ses.service.integrationhub.IntegrationHubWebhookDeliveryReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** NF05の管理API。既存integration-hub rootの管理者境界を再利用する。 */
@RestController
@RequestMapping("/api/integration-hub")
@PreAuthorize("hasRole('管理者')")
@RequiredArgsConstructor
public class IntegrationHubOutboundAdminApiController {
    private final IntegrationHubManagementService management;
    private final com.ses.service.integrationhub.IntegrationHubBulkReplayService bulkReplay;

    @PostMapping("/clients")
    public ApiResult<String> client(@RequestBody IntegrationHubManagementService.ClientCommand command) {
        return ApiResult.success(management.registerClient(command));
    }

    @PostMapping("/client-scopes")
    public ApiResult<Void> scope(@RequestBody IntegrationHubManagementService.ScopeCommand command) {
        management.grantScope(command);
        return ApiResult.success(null);
    }

    @PostMapping("/subscriptions")
    public ApiResult<IntegrationHubManagementService.SubscriptionView> register(
            @RequestBody IntegrationHubManagementService.SubscriptionCommand command) {
        management.validateEndpoint(command.endpoint());
        return ApiResult.success(management.register(command));
    }

    @PostMapping("/credentials")
    public ApiResult<IntegrationHubManagementService.IssuedCredential> issue(@RequestBody CredentialCommand command) {
        return ApiResult.success(management.issue(command.clientId(), command.legalEntityId(), command.version(), command.keyId()));
    }

    @DeleteMapping("/credentials")
    public ApiResult<Void> revoke(@RequestBody CredentialCommand command) {
        management.revoke(command.clientId(), command.legalEntityId(), command.version());
        return ApiResult.success(null);
    }

    @PostMapping("/deliveries/{id}/replay")
    public ApiResult<Long> replay(@PathVariable Long id, @RequestBody ReplayCommand command, Authentication authentication) {
        return ApiResult.success(management.replay(id, command.legalEntityId(), command.reasonCode(), authentication));
    }

    @PostMapping("/deliveries/bulk-replay")
    public ApiResult<com.ses.service.integrationhub.IntegrationHubBulkReplayService.Result> bulkReplay(
            @RequestBody com.ses.service.integrationhub.IntegrationHubBulkReplayService.Command command, Authentication authentication) {
        return ApiResult.success(bulkReplay.replay(command, authentication));
    }

    public record CredentialCommand(String clientId, Long legalEntityId, int version, String keyId) { }
    public record ReplayCommand(Long legalEntityId, String reasonCode) { }
}
