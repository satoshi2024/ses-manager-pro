package com.ses.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.CsvUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.asset.ExternalAccountReferenceDto;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.ExternalAccountService;
import com.ses.service.AssetScopeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/external-accounts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー', '営業')")
public class ExternalAccountApiController {

    private final ExternalAccountService externalAccountService;
    private final AssetScopeService assetScopeService;
    private final SysUserMapper sysUserMapper;

    @GetMapping
    public ApiResult<IPage<ExternalAccountReferenceDto>> search(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String assigneeType,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String status) {
        IPage<ExternalAccountReference> result = externalAccountService.searchAccountsScoped(page, size, systemId,
                assigneeType, assigneeId, status,
                assetScopeService.getAccessibleEngineerIds(SecurityUtils.currentRole(), SecurityUtils.currentUserId()));
        return ApiResult.success(toDtoPage(result));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<ExternalAccountReferenceDto> register(@RequestBody AccountRegisterRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        ExternalAccountReference registered = externalAccountService.registerAccountReference(
                req.getSystemId(),
                req.getAccountIdentifier(),
                req.getAssigneeType(),
                req.getAssigneeId(),
                req.getPermissionLevel(),
                currentUserId
        );
        return ApiResult.success(ExternalAccountReferenceDto.from(registered));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<ExternalAccountReferenceDto> update(@PathVariable Long id, @RequestBody AccountRegisterRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        ExternalAccountReference updated = externalAccountService.updateAccountReference(
                id,
                req.getAccountIdentifier(),
                req.getPermissionLevel(),
                currentUserId
        );
        return ApiResult.success(ExternalAccountReferenceDto.from(updated));
    }

    @PostMapping("/{id}/confirm-revoke")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<ExternalAccountReferenceDto> confirmRevoke(
            @PathVariable Long id,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // principalの文字列/外部subjectをユーザーIDとして扱わず、必ずsys_userの実体へ解決する。
        String username = SecurityUtils.currentUsername();
        Long currentUserId = null;
        if (username != null && !username.isBlank()) {
            SysUser principalUser = sysUserMapper.selectByUsername(username);
            currentUserId = principalUser != null ? principalUser.getId() : null;
        }
        // principalをsys_user.idへ解決できない場合はサービス側で拒否する。SYSTEMへの降格は禁止。
        ExternalAccountReference confirmed = externalAccountService.confirmRevokeManually(
                id, currentUserId, correlationId, idempotencyKey);
        return ApiResult.success(ExternalAccountReferenceDto.from(confirmed));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String assigneeType,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String status) {
        IPage<ExternalAccountReference> result = externalAccountService.searchAccountsScoped(1, 10000, systemId,
                assigneeType, assigneeId, status,
                assetScopeService.getAccessibleEngineerIds(SecurityUtils.currentRole(), SecurityUtils.currentUserId()));
        StringBuilder csv = new StringBuilder(CsvUtils.UTF8_BOM);
        CsvUtils.appendLine(csv, "id", "system_id", "account_identifier", "status", "revoke_requested_by",
                "actor_type", "confirmation_source", "human_user_id");
        for (ExternalAccountReference reference : result.getRecords()) {
            ExternalAccountReferenceDto dto = ExternalAccountReferenceDto.from(reference);
            CsvUtils.appendLine(csv,
                    String.valueOf(dto.getId()), String.valueOf(dto.getSystemId()), dto.getAccountIdentifier(),
                    dto.getStatus(), dto.getRevokeRequestedBy() == null ? "" : String.valueOf(dto.getRevokeRequestedBy()),
                    dto.getActorTypeDisplay(), dto.getConfirmationSourceDisplay(),
                    dto.getHumanUserId() == null ? "" : String.valueOf(dto.getHumanUserId()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=external-account-references.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private IPage<ExternalAccountReferenceDto> toDtoPage(IPage<ExternalAccountReference> source) {
        Page<ExternalAccountReferenceDto> page = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        page.setRecords(source.getRecords().stream().map(ExternalAccountReferenceDto::from).toList());
        return page;
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
