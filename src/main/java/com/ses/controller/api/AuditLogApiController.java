package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.CsvUtils;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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
        Page<AuditLog> result = auditLogService.page(safe.getCurrent(), safe.getSize(), username, method);
        if (result != null && result.getRecords() != null) {
            result.getRecords().forEach(this::normalizeAttribution);
        }
        return ApiResult.success(result);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String method) {
        Page<AuditLog> page = auditLogService.page(1, 10000, username, method);
        StringBuilder csv = new StringBuilder(CsvUtils.UTF8_BOM);
        CsvUtils.appendLine(csv, "created_at", "username", "method", "uri", "status",
                "actor_type", "confirmation_source", "human_user_id", "reference_type", "reference_id",
                "before_state", "after_state", "correlation_id", "idempotency_key");
        if (page == null || page.getRecords() == null) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-logs.csv")
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (AuditLog log : page.getRecords()) {
            normalizeAttribution(log);
            CsvUtils.appendLine(csv,
                    String.valueOf(log.getCreatedAt()), log.getUsername(), log.getMethod(), log.getUri(),
                    String.valueOf(log.getStatus()), log.getActorType(), log.getConfirmationSource(),
                    log.getHumanUserId() == null ? "" : String.valueOf(log.getHumanUserId()),
                    log.getReferenceType(), log.getReferenceId() == null ? "" : String.valueOf(log.getReferenceId()),
                    log.getBeforeState(), log.getAfterState(), log.getCorrelationId(), log.getIdempotencyKey());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-logs.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void normalizeAttribution(AuditLog log) {
        if (log == null) {
            return;
        }
        try {
            com.ses.common.audit.ActorType actor = com.ses.common.audit.ActorType.valueOf(log.getActorType());
            com.ses.common.audit.ConfirmationSource source =
                    com.ses.common.audit.ConfirmationSource.valueOf(log.getConfirmationSource());
            boolean validPair = switch (actor) {
                case HUMAN -> source == com.ses.common.audit.ConfirmationSource.MANUAL_API
                        && log.getHumanUserId() != null && log.getHumanUserId() > 0;
                case SYSTEM -> source == com.ses.common.audit.ConfirmationSource.SCHEDULER_POLL
                        && log.getHumanUserId() == null;
                case PROVIDER -> (source == com.ses.common.audit.ConfirmationSource.PROVIDER_SYNC
                        || source == com.ses.common.audit.ConfirmationSource.PROVIDER_CALLBACK)
                        && log.getHumanUserId() == null;
                case LEGACY_UNRESOLVED -> source == com.ses.common.audit.ConfirmationSource.LEGACY_UNRESOLVED
                        && log.getHumanUserId() == null;
            };
            if (validPair) {
                return;
            }
        } catch (RuntimeException ignored) {
            // 旧行/不整合行は推測せず、明示的なLEGACY_UNRESOLVEDとして返す。
        }
        log.setActorType(com.ses.common.audit.ActorType.LEGACY_UNRESOLVED.name());
        log.setConfirmationSource(com.ses.common.audit.ConfirmationSource.LEGACY_UNRESOLVED.name());
        log.setHumanUserId(null);
    }
}
