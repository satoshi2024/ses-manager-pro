package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.bpmigration.BpMigrationExceptionDto;
import com.ses.service.BpMigrationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bp-migrations")
@RequiredArgsConstructor
public class BpMigrationApiController {

    private final BpMigrationService bpMigrationService;

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<String> runMigration() {
        requireAdmin();
        bpMigrationService.migrateLegacyBpData();
        return ApiResult.success("移行処理が完了しました");
    }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<List<BpMigrationExceptionDto>> getExceptions() {
        requireAdmin();
        return ApiResult.success(bpMigrationService.getMigrationExceptions());
    }

    @PostMapping("/resolve")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<Void> resolveException(@RequestBody ResolveReq req) {
        requireAdmin();
        bpMigrationService.resolveMigrationException(req.getSourceType(), req.getSourceId(), req.getTargetBpCompanyId());
        return ApiResult.success(null);
    }

    @Data
    public static class ResolveReq {
        private String sourceType;
        private Long sourceId;
        private Long targetBpCompanyId;
    }

    private void requireAdmin() {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(403, "この操作は管理者のみ実行できます");
        }
    }
}
