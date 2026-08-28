package com.ses.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.AssetEvent;
import com.ses.service.AssetAssignmentService;
import com.ses.service.AssetEventService;
import com.ses.service.AssetService;
import com.ses.service.AssetScopeService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー', '営業')")
public class AssetApiController {

    private final AssetService assetService;
    private final AssetEventService assetEventService;
    private final AssetAssignmentService assetAssignmentService;
    private final AssetScopeService assetScopeService;

    @GetMapping
    public ApiResult<IPage<Asset>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ownerCompanyId) {
        IPage<Asset> result = assetService.searchAssetsScoped(page, size, keyword, category, status, ownerCompanyId,
                assetScopeService.getAccessibleAssetIds(SecurityUtils.currentRole(), SecurityUtils.currentUserId()));
        return ApiResult.success(result);
    }

    @GetMapping("/{id}")
    public ApiResult<Asset> getById(@PathVariable Long id) {
        Asset asset = assetService.getById(id);
        if (asset == null) {
            return ApiResult.error(404, "資産が見つかりません。");
        }
        assertAccessible(id);
        return ApiResult.success(asset);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<Asset> create(@RequestBody Asset asset) {
        Long currentUserId = SecurityUtils.currentUserId();
        Asset created = assetService.createAsset(asset, currentUserId);
        return ApiResult.success(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<Asset> update(@PathVariable Long id, @RequestBody Asset asset) {
        asset.setId(id);
        Long currentUserId = SecurityUtils.currentUserId();
        Asset updated = assetService.updateAsset(asset, currentUserId);
        return ApiResult.success(updated);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<Asset> changeStatus(@PathVariable Long id, @RequestBody StatusChangeRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        Asset updated = assetService.changeStatus(id, req.getToStatus(), req.getReason(), currentUserId, req.getEvidenceDocId());
        return ApiResult.success(updated);
    }

    @PostMapping("/{id}/dispose")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<Asset> dispose(@PathVariable Long id, @RequestBody(required = false) StatusChangeRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        String reason = req != null ? req.getReason() : null;
        Long evidenceDocId = req != null ? req.getEvidenceDocId() : null;
        Asset disposed = assetService.disposeAsset(id, reason, currentUserId, evidenceDocId);
        return ApiResult.success(disposed);
    }

    @PostMapping("/{id}/report-lost")
    @PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー', '営業')")
    public ApiResult<Asset> reportLost(@PathVariable Long id, @RequestBody(required = false) StatusChangeRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        String details = req != null ? req.getReason() : null;
        Long evidenceDocId = req != null ? req.getEvidenceDocId() : null;
        assertAccessible(id);
        Asset lost = assetService.reportLost(id, details, currentUserId, evidenceDocId);
        return ApiResult.success(lost);
    }

    @GetMapping("/{id}/events")
    public ApiResult<List<AssetEvent>> getEvents(@PathVariable Long id) {
        assertAccessible(id);
        List<AssetEvent> events = assetEventService.getEventsByAssetId(id);
        return ApiResult.success(events);
    }

    @GetMapping("/{id}/assignments")
    public ApiResult<List<AssetAssignment>> getAssignments(@PathVariable Long id) {
        assertAccessible(id);
        List<AssetAssignment> assignments = assetAssignmentService.getAssignmentHistoryByAssetId(id);
        return ApiResult.success(assignments);
    }

    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ownerCompanyId,
            HttpServletResponse response) throws IOException {
        IPage<Asset> page = assetService.searchAssetsScoped(1, 10000, keyword, category, status, ownerCompanyId,
                assetScopeService.getAccessibleAssetIds(SecurityUtils.currentRole(), SecurityUtils.currentUserId()));
        List<Asset> records = page.getRecords();

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"assets_export.csv\"");

        try (PrintWriter writer = new PrintWriter(response.getOutputStream(), false, StandardCharsets.UTF_8)) {
            // UTF-8 BOM
            response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

            writer.println("資産タグ,資産名称,区分,シリアル番号,ステータス,保管場所,取得日,取得価格,保証満了日,リース満了日,備考");
            for (Asset a : records) {
                writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        escapeCsv(a.getAssetTag()),
                        escapeCsv(a.getAssetName()),
                        escapeCsv(a.getCategory()),
                        escapeCsv(a.getSerialNo()),
                        escapeCsv(a.getStatus()),
                        escapeCsv(a.getLocation()),
                        a.getPurchaseDate() != null ? a.getPurchaseDate().toString() : "",
                        a.getPurchasePrice() != null ? a.getPurchasePrice().toString() : "",
                        a.getWarrantyExpiry() != null ? a.getWarrantyExpiry().toString() : "",
                        a.getLeaseExpiry() != null ? a.getLeaseExpiry().toString() : "",
                        escapeCsv(a.getNote())
                );
            }
            writer.flush();
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }

    private void assertAccessible(Long assetId) {
        if (!assetScopeService.isAccessible(assetId, SecurityUtils.currentRole(), SecurityUtils.currentUserId())) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    @Data
    public static class StatusChangeRequest {
        private String toStatus;
        private String reason;
        private Long evidenceDocId;
    }
}
