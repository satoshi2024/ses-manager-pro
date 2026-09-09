package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.security.OwnershipRepairRequest;
import com.ses.entity.OwnershipRepairQueue;
import com.ses.service.security.OwnershipRepairService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** NF02/NF03 unresolved ownershipの管理者専用チェック・修復API。 */
@RestController
@RequestMapping("/api/admin/nf02-nf03/ownership-repair")
@RequiredArgsConstructor
@PreAuthorize("hasRole('管理者')")
public class OwnershipRepairApiController {
    private final OwnershipRepairService service;

    @GetMapping
    public ApiResult<List<OwnershipRepairQueue>> list() {
        return ApiResult.success(service.listPending());
    }

    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> summary() {
        return ApiResult.success(service.summary());
    }

    @PostMapping("/{id}/resolve")
    public ApiResult<Void> resolve(@PathVariable Long id, @Valid @RequestBody OwnershipRepairRequest request) {
        service.resolve(id, request.getTenantId(), request.getReason(), request.getEvidence());
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/assign")
    public ApiResult<Void> assign(@PathVariable Long id, @RequestParam Long assigneeUserId) {
        service.assign(id, assigneeUserId);
        return ApiResult.success(null);
    }
}
