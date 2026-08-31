package com.ses.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AssetInventoryItem;
import com.ses.entity.AssetInventoryRun;
import com.ses.service.AssetInventoryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/asset-inventory")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー')")
public class AssetInventoryApiController {

    private final AssetInventoryService assetInventoryService;

    @GetMapping("/runs")
    public ApiResult<IPage<AssetInventoryRun>> listRuns(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        IPage<AssetInventoryRun> runs = assetInventoryService.searchRuns(page, size, status);
        return ApiResult.success(runs);
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<AssetInventoryRun> startRun(@RequestBody StartRunRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        LocalDate targetDate = req.getTargetDate() != null ? req.getTargetDate() : LocalDate.now();
        AssetInventoryRun run = assetInventoryService.startInventoryRun(
                req.getInventoryCode(),
                req.getTitle(),
                targetDate,
                currentUserId
        );
        return ApiResult.success(run);
    }

    @GetMapping("/runs/{runId}/items")
    public ApiResult<List<AssetInventoryItem>> getItems(@PathVariable Long runId) {
        List<AssetInventoryItem> items = assetInventoryService.getItemsByRunId(runId);
        return ApiResult.success(items);
    }

    @PostMapping("/items/{itemId}/check")
    public ApiResult<AssetInventoryItem> recordCheck(@PathVariable Long itemId, @RequestBody ItemCheckRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        AssetInventoryItem checked = assetInventoryService.recordItemCheck(
                itemId,
                req.getObservedStatus(),
                req.getObservedLocation(),
                req.getDiscrepancyType(),
                req.getDiscrepancyReason(),
                req.getResolutionAction(),
                currentUserId
        );
        return ApiResult.success(checked);
    }

    @PostMapping("/runs/{runId}/complete")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<AssetInventoryRun> completeRun(@PathVariable Long runId) {
        Long currentUserId = SecurityUtils.currentUserId();
        AssetInventoryRun completed = assetInventoryService.completeInventoryRun(runId, currentUserId);
        return ApiResult.success(completed);
    }

    @Data
    public static class StartRunRequest {
        private String inventoryCode;
        private String title;
        private LocalDate targetDate;
    }

    @Data
    public static class ItemCheckRequest {
        private String observedStatus;
        private String observedLocation;
        private String discrepancyType;
        private String discrepancyReason;
        private String resolutionAction;
    }
}
