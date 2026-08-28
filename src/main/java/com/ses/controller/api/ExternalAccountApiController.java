package com.ses.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.service.ExternalAccountService;
import com.ses.service.AssetScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/external-accounts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー', '営業')")
public class ExternalAccountApiController {

    private final ExternalAccountService externalAccountService;
    private final AssetScopeService assetScopeService;

    @GetMapping
    public ApiResult<IPage<ExternalAccountReference>> search(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String assigneeType,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String status) {
        IPage<ExternalAccountReference> result = externalAccountService.searchAccountsScoped(page, size, systemId,
                assigneeType, assigneeId, status,
                assetScopeService.getAccessibleEngineerIds(SecurityUtils.currentRole(), SecurityUtils.currentUserId()));
        return ApiResult.success(result);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<ExternalAccountReference> register(@RequestBody AccountRegisterRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        ExternalAccountReference registered = externalAccountService.registerAccountReference(
                req.getSystemId(),
                req.getAccountIdentifier(),
                req.getAssigneeType(),
                req.getAssigneeId(),
                req.getPermissionLevel(),
                currentUserId
        );
        return ApiResult.success(registered);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<ExternalAccountReference> update(@PathVariable Long id, @RequestBody AccountRegisterRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        ExternalAccountReference updated = externalAccountService.updateAccountReference(
                id,
                req.getAccountIdentifier(),
                req.getPermissionLevel(),
                currentUserId
        );
        return ApiResult.success(updated);
    }

    @PostMapping("/{id}/confirm-revoke")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<ExternalAccountReference> confirmRevoke(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.currentUserId();
        ExternalAccountReference confirmed = externalAccountService.confirmRevoke(id, currentUserId);
        return ApiResult.success(confirmed);
    }

    @GetMapping("/systems")
    public ApiResult<List<ExternalAccountSystem>> getSystems() {
        return ApiResult.success(externalAccountService.getAllSystems());
    }

    @PostMapping("/systems")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<ExternalAccountSystem> saveSystem(@RequestBody ExternalAccountSystem system) {
        ExternalAccountSystem saved = externalAccountService.saveSystem(system);
        return ApiResult.success(saved);
    }

    @Data
    public static class AccountRegisterRequest {
        private Long systemId;
        private String accountIdentifier;
        private String assigneeType;
        private Long assigneeId;
        private String permissionLevel;
    }
}
