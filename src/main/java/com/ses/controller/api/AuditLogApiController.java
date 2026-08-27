package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 監査ログAPI（管理者のみ。SecurityConfigで /api/audit-logs/** を hasRole("管理者") に制限）。
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("hasRole('管理者')")
public class AuditLogApiController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ApiResult<Page<AuditLog>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String method) {
        // size<=0 でページング無効化(全件取得)になるのを防ぐ。監査ログは全テーブル中で最も行数が伸びる。
        Page<AuditLog> safe = com.ses.common.util.PageUtils.safePage(current, size);
        return ApiResult.success(auditLogService.page(safe.getCurrent(), safe.getSize(), username, method));
    }
}
