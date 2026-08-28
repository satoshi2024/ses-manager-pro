package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AssetAssignment;
import com.ses.service.AssetAssignmentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api/asset-assignments")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー', '営業')")
public class AssetAssignmentApiController {

    private final AssetAssignmentService assetAssignmentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<AssetAssignment> assign(@RequestBody AssignmentCreateRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        AssetAssignment assignment = assetAssignmentService.createAssignment(
                req.getAssetId(),
                req.getAssigneeType(),
                req.getAssigneeId(),
                req.getStartDate(),
                req.getExpectedReturnDate(),
                req.getHandoverEvidenceDocId(),
                req.getNote(),
                currentUserId
        );
        return ApiResult.success(assignment);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('管理者', 'HR')")
    public ApiResult<AssetAssignment> returnAsset(@PathVariable Long id, @RequestBody(required = false) ReturnRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        LocalDate returnDate = req != null && req.getActualReturnDate() != null ? req.getActualReturnDate() : LocalDate.now();
        Long docId = req != null ? req.getReturnEvidenceDocId() : null;
        String note = req != null ? req.getNote() : null;

        AssetAssignment returned = assetAssignmentService.returnAssignment(
                id,
                returnDate,
                docId,
                note,
                currentUserId
        );
        return ApiResult.success(returned);
    }

    @PostMapping("/{id}/waive")
    @PreAuthorize("hasAnyRole('管理者')")
    public ApiResult<AssetAssignment> waive(@PathVariable Long id, @RequestBody WaiveRequest req) {
        Long currentUserId = SecurityUtils.currentUserId();
        AssetAssignment waived = assetAssignmentService.waiveAssignment(
                id,
                req.getReason(),
                req.getApprovalRequestId(),
                currentUserId
        );
        return ApiResult.success(waived);
    }

    @Data
    public static class AssignmentCreateRequest {
        private Long assetId;
        private String assigneeType;
        private Long assigneeId;
        private LocalDate startDate;
        private LocalDate expectedReturnDate;
        private Long handoverEvidenceDocId;
        private String note;
    }

    @Data
    public static class ReturnRequest {
        private LocalDate actualReturnDate;
        private Long returnEvidenceDocId;
        private String note;
    }

    @Data
    public static class WaiveRequest {
        private String reason;
        private Long approvalRequestId;
    }
}
